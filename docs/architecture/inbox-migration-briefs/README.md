# Inbox migration: briefs

Drafts of the briefs for the step-by-step migration in `../inbox-split.md`. They belong in the private product repo
(`homelab/briefs/`), which the inbox cannot write to, so they live here until the designer copies them. Before copying:
check the ids (H-020 to H-027 are provisional: the last id seen was H-019), add each to `homelab/ROADMAP.md`, and keep
`status: draft`; Chris approves on the Briefs tab. The `build.kit` lists are suggestions for the build-kit round.

| Brief | Slices | Depends on | Needs the user |
|---|---|---|---|
| H-020 contract and tests | C1, C2, C3 | none | no |
| H-021 frontend scaffold | F1, F2, F3 | H-020 (C1); Q3, Q4 | merge of F3 (root script) |
| H-022 pages in TypeScript | F4 to F12 | H-021 | no; then Q8: stop or go on |
| H-023 backend and database | B1 to B8 | H-020 (C2); Q1, Q2, Q3, Q5, Q6, Q8 | package, root scripts |
| H-024 reads and small stores | M1 to M6 | H-023, H-022 (F4a, F5a, F6a, F7a) | no |
| H-025 runners | R1 to R3 | H-020 (C3), Q7; R3 needs H-023 B1 | no |
| H-026 core in shadow | K1 to K9 | H-023, H-024, H-025 | no |
| H-027 cut-over and clean-up | X1, X2 | all above, Q9 | yes |

H-020, then H-021/H-022 and H-023/H-025 can run side by side. Stopping after H-022 is a complete result. Merge inbox
PRs one at a time and not while long tasks run (a restart cuts them off).
