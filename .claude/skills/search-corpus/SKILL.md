---
name: search-corpus
description: Rules for the private search index (H-012): sources, chunking, metadata, secret scrubbing before indexing, and incremental re-indexing. Use when building or changing the indexer, the query side or the cited answers.
---

Source: brief H-012 (`homelab/briefs/H-012-llm-router-and-search.md` in the product repo). Details the brief leaves open (which transcripts may be read, the embedding model) come from the planner's decision; do not invent them.

## Sources
- Second brain wiki, briefs, visions, roadmaps and research notes, session transcripts, repo docs and code, and F-023 memory.
- Which transcripts the indexer may read is an open question. Inbox sessions are copied out for the dashboard (claude has no access to `/var/lib/inbox`); interactive sessions live in `~/.claude`.

## Chunking and metadata
- Chunk per source type along its natural structure: headings for documents, turns for transcripts. Chunking rules per source are set in the build task.
- Every result shows source, location in it (heading or turn), date and project, and can be filtered by project and source type.

## Secret scrubbing
- Scrub secrets before indexing. A fake secret planted in a test transcript must be neither in the index nor returned by a query for it; keep that test.

## Re-indexing
- After a source changes, re-index only that source. A normal nightly sync never needs a full re-index.
- A script rebuilds the whole index from its sources, so a rebuilt box gets it back.

## Answers
- A question returns a short answer with citations linking to its sources. The router writes it: local tier by default, claude tier for questions marked hard.
- When nothing relevant is found, say so instead of guessing.
- Search is read-only: it never changes the vault, a repo or the memory branch.
