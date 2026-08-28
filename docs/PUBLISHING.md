# Publishing the write-up

Two copies of the same article, differing only in front-matter.

| File | For |
|---|---|
| `article.md` | Medium. Paste the Markdown straight into the editor — it converts headings, code blocks and tables on paste. |
| `article.devto.md` | dev.to and Hashnode. Carries YAML front-matter; both accept it as-is. |

`published: false` in the front-matter means dev.to saves it as a draft rather than
publishing immediately. Flip it to `true`, or press Publish in their editor, once you have
read it over.

`canonical_url` points at the repository. Set it to whichever copy goes live **first**, on
every other copy, so search engines credit one canonical version rather than treating the
rest as duplicates. If Medium is first, put the Medium URL in the dev.to front-matter; Medium
has an equivalent field under story settings.

Neither file is posted automatically — publishing to either platform needs account
credentials, which stay with you.
