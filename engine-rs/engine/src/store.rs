//! Content-addressed object store: a git `objects/` directory that only ever
//! gains packs. Objects are immutable, so nothing in it needs invalidating.

use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;

use anyhow::{Context, Result};
use gix_hash::{Kind, ObjectId};
use gix_object::{Exists, Find, FindExt};

#[derive(Clone)]
pub struct Store {
    objects_dir: PathBuf,
    odb: gix_odb::HandleArc,
}

pub struct IngestOutcome {
    pub objects: u32,
    pub pack_bytes: u64,
}

impl Store {
    pub fn open(objects_dir: impl AsRef<Path>) -> Result<Self> {
        let objects_dir = objects_dir.as_ref().to_owned();
        std::fs::create_dir_all(objects_dir.join("pack"))?;
        let odb = gix_odb::at(&objects_dir, Kind::Sha1)?.into_arc()?;
        Ok(Self { objects_dir, odb })
    }

    pub fn contains(&self, id: &ObjectId) -> bool {
        self.odb.exists(id)
    }

    /// Writes a pack stream into the store and indexes it.
    pub fn ingest(&self, pack: impl Read) -> Result<IngestOutcome> {
        let mut pack = BufReader::with_capacity(256 * 1024, pack);
        let outcome = gix_pack::Bundle::write_to_directory(
            &mut pack,
            Some(&self.objects_dir.join("pack")),
            &mut gix_features::progress::Discard,
            &AtomicBool::new(false),
            None::<gix_object::find::Never>,
            Kind::Sha1,
            gix_pack::bundle::write::Options::default(),
        )
        .context("indexing pack")?;
        let pack_bytes = outcome
            .data_path
            .as_ref()
            .and_then(|path| std::fs::metadata(path).ok())
            .map_or(0, |meta| meta.len());
        Ok(IngestOutcome {
            objects: outcome.index.num_objects,
            pack_bytes,
        })
    }

    pub fn tree<'a>(&self, id: &ObjectId, buf: &'a mut Vec<u8>) -> Result<gix_object::TreeRef<'a>> {
        Ok(self.odb.find_tree(id, buf)?)
    }

    pub fn commit_tree(&self, id: &ObjectId) -> Result<ObjectId> {
        let mut buf = Vec::new();
        Ok(self.odb.find_commit(id, &mut buf)?.tree())
    }

    pub fn blob(&self, id: &ObjectId) -> Result<Vec<u8>> {
        let mut buf = Vec::new();
        let data = self
            .odb
            .try_find(id, &mut buf)?
            .with_context(|| format!("missing blob {id}"))?;
        Ok(data.data.to_vec())
    }
}
