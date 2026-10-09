# Historical approved baseline publication boundary

The repository is a single monorepo (`/Users/buynnex-corp/dev`, `ChamberUS/DEV`). The historical dependency commit records previously approved U/V/W work separately from Package B and Package A. No new Service, signer or packaging source change was made by Package A.

- RC1 source evidence: `docs/qa/v21w/local-rc1/source-freeze-evidence.tar` (kept local, excluded from Git).
- Exact approved source hashes: `docs/qa/v21w/local-rc1/approved-source-freeze.json`.
- V2.1V final report: `docs/qa/v21v/report.md`.
- V2.1W final manifest: `docs/qa/v21w/local-rc1/manifest.md`.
- V2.1U final report: `../byx-local-service/docs/qa/v21u/report.md`.

Historical boundary: **111 source/build files and 7 explicitly documented deletions**. Panel 65, Service 30, packaging 16. The retired Service QA classes are moved to test sources; the retired legacy signer client remains absent as qualified. The LOCAL_QA Panel factory is a pre-existing dependency of the profile split recorded in V2.1V preflight; its bytes match the qualified Package B snapshot and its small source was reviewed independently. It never enters DEFAULT.

Package B boundary: **58 source/resource/test files** plus approved B report, code map and sanitized evidence. Overlapping files are staged from their exact B-qualified snapshot, never from an unknown blend.

Package A boundary: current functional corrections, 18 new tests, native visual harness and final QA evidence. The final Panel suite is 866/0/0/1. All original working-tree file contents are preserved; Git index blobs separate historical/B versions without restoring or discarding workspace files.

Unrelated projects, raw captures, local state, private/provisioning credentials, build outputs, archives, logs, ignored QA app/probes and caches remain local. The 128 unpublished ancestor commits contain only these three already-related projects; a filename and text-blob audit found no secret material or blobs above 10 MB. No neighboring repository is published.
