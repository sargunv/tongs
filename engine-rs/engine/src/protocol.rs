//! Git smart-HTTP protocol v2 `fetch`, minus the HTTP.
//!
//! The engine never opens sockets. Callers POST [`FetchRequest::body`] to
//! `<repo>.git/git-upload-pack` with [`FetchRequest::HEADERS`] and hand the
//! response body to [`PackStream`], which yields raw pack bytes.

use std::io::{self, Read};

use gix_hash::ObjectId;

/// Object filter sent with a fetch. Explicitly wanted objects are always sent;
/// the filter only prunes what the server would otherwise walk into.
#[derive(Clone, Copy, Debug)]
pub enum Filter {
    /// Commits without their trees.
    TreeZero,
    /// Commits and trees, no blobs.
    BlobNone,
}

impl Filter {
    fn spec(self) -> &'static str {
        match self {
            Filter::TreeZero => "tree:0",
            Filter::BlobNone => "blob:none",
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct FetchRequest {
    pub wants: Vec<ObjectId>,
    pub filter: Option<Filter>,
    /// Shallow depth for commit wants. Without it, wanting a commit sends its
    /// whole history, whatever the filter: 1.1M objects for nixpkgs.
    pub deepen: Option<u32>,
}

impl FetchRequest {
    pub const HEADERS: [(&str, &str); 3] = [
        ("Git-Protocol", "version=2"),
        ("Content-Type", "application/x-git-upload-pack-request"),
        ("Accept", "application/x-git-upload-pack-result"),
    ];

    pub fn body(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(64 + self.wants.len() * 50);
        pkt_line(&mut out, b"command=fetch\n");
        pkt_line(&mut out, b"object-format=sha1\n");
        out.extend_from_slice(b"0001");
        pkt_line(&mut out, b"ofs-delta\n");
        pkt_line(&mut out, b"no-progress\n");
        if let Some(filter) = self.filter {
            pkt_line(&mut out, format!("filter {}\n", filter.spec()).as_bytes());
        }
        if let Some(depth) = self.deepen {
            pkt_line(&mut out, format!("deepen {depth}\n").as_bytes());
        }
        for want in &self.wants {
            pkt_line(&mut out, format!("want {want}\n").as_bytes());
        }
        pkt_line(&mut out, b"done\n");
        out.extend_from_slice(b"0000");
        out
    }
}

fn pkt_line(out: &mut Vec<u8>, payload: &[u8]) {
    out.extend_from_slice(format!("{:04x}", payload.len() + 4).as_bytes());
    out.extend_from_slice(payload);
}

/// Demultiplexes a v2 fetch response into the pack data it carries.
pub struct PackStream<R> {
    inner: R,
    state: State,
    pkt: Vec<u8>,
    pos: usize,
}

#[derive(PartialEq)]
enum State {
    Preamble,
    Packfile,
    Done,
}

impl<R: Read> PackStream<R> {
    pub fn new(inner: R) -> Self {
        Self {
            inner,
            state: State::Preamble,
            pkt: Vec::new(),
            pos: 0,
        }
    }

    /// Reads one pkt-line into `self.pkt`. Returns `None` for flush, delim,
    /// and response-end packets.
    fn next_pkt(&mut self) -> io::Result<Option<()>> {
        let mut len = [0u8; 4];
        self.inner.read_exact(&mut len)?;
        let len = std::str::from_utf8(&len)
            .ok()
            .and_then(|s| usize::from_str_radix(s, 16).ok())
            .ok_or_else(|| invalid("bad pkt-line length"))?;
        if len < 4 {
            return Ok(None);
        }
        self.pkt.resize(len - 4, 0);
        self.inner.read_exact(&mut self.pkt)?;
        if let Some(msg) = self.pkt.strip_prefix(b"ERR ") {
            return Err(remote_error(msg));
        }
        Ok(Some(()))
    }
}

impl<R: Read> Read for PackStream<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            match self.state {
                State::Done => return Ok(0),
                State::Preamble => {
                    // Skip acknowledgments, shallow-info, etc. until the pack.
                    if self.next_pkt()?.is_some() && self.pkt == b"packfile\n" {
                        self.state = State::Packfile;
                        self.pos = self.pkt.len();
                    }
                }
                State::Packfile => {
                    if self.pos < self.pkt.len() {
                        let n = buf.len().min(self.pkt.len() - self.pos);
                        buf[..n].copy_from_slice(&self.pkt[self.pos..self.pos + n]);
                        self.pos += n;
                        return Ok(n);
                    }
                    if self.next_pkt()?.is_none() {
                        self.state = State::Done;
                        continue;
                    }
                    match self.pkt.first() {
                        Some(1) => self.pos = 1,
                        Some(2) => self.pos = self.pkt.len(),
                        Some(3) => return Err(remote_error(&self.pkt[1..])),
                        _ => return Err(invalid("unknown sideband channel")),
                    }
                }
            }
        }
    }
}

fn invalid(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.to_owned())
}

fn remote_error(msg: &[u8]) -> io::Error {
    io::Error::other(format!(
        "remote: {}",
        String::from_utf8_lossy(msg).trim_end()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pkt(payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        pkt_line(&mut out, payload);
        out
    }

    #[test]
    fn demuxes_sideband_after_packfile_header() {
        let mut response = Vec::new();
        response.extend(pkt(b"packfile\n"));
        response.extend(pkt(b"\x02progress"));
        response.extend(pkt(b"\x01PACK"));
        response.extend(pkt(b"\x01data"));
        response.extend_from_slice(b"0000");
        let mut out = Vec::new();
        PackStream::new(&response[..])
            .read_to_end(&mut out)
            .unwrap();
        assert_eq!(out, b"PACKdata");
    }

    #[test]
    fn surfaces_remote_errors() {
        let response = pkt(b"ERR upload-pack: not our ref");
        let err = PackStream::new(&response[..])
            .read_to_end(&mut Vec::new())
            .unwrap_err();
        assert!(err.to_string().contains("not our ref"));
    }
}
