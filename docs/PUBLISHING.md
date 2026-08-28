# Publishing the write-up

Four files, one story, four registers. None of them posts itself — every platform needs
account credentials, which stay with you.

| File | Platform | Length | Tone |
|---|---|---|---|
| `article.md` | Medium | ~1,700 words | Long-form narrative. Paste the Markdown straight in; it converts headings, code blocks and tables on paste. |
| `article.devto.md` | dev.to, Hashnode | ~1,700 words | Same text with YAML front-matter, which both accept as-is. |
| `post-hackernews.md` | Hacker News | ~350 word comment | Link post to the repo, plus a first comment. Plain, no marketing register. |
| `post-reddit.md` | r/java, r/SpringBoot, r/LocalLLaMA | ~450 words each | Text post. Two variants: engineering detail for the Java subs, self-hosting outcomes for r/LocalLLaMA. |

## Order matters

Post to **one** platform first and let it be the canonical copy, then point the others at it.

1. Set `canonical_url` in `article.devto.md` to whichever URL went live first. Medium has the
   same field under story settings. Without this, search engines treat the second copy as
   duplicate content and usually suppress it.
2. `published: false` in the dev.to front-matter means it saves as a draft. Flip it to `true`
   once you've read it over.

## Things that will get you downvoted

- **Hacker News:** submit as a *link* post to the repo, then add the context as your first
  comment. Do not submit the article as a text post, and do not open with a pitch. The
  audience rewards specifics and honesty about limitations.
- **Reddit:** every one of these subs has a self-promotion rule, and r/java enforces it. Post
  to one sub at a time, a few days apart. A link-only post to your own project reads as spam;
  put the substance in the body.
- Both audiences respond to *what went wrong*, not to a feature list. That is why the short
  posts lead with the filter-ordering constraints and the silent reactive bugs rather than
  with what the library does.

## Media

`media/console-demo.gif` is embedded in the long-form copies via an absolute GitHub raw URL,
so it renders on Medium and dev.to without re-uploading. `media/console-nvidia.png` is a
single still for anywhere that will not animate.
