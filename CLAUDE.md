# pdf-lite (Pdfrid)

An offline PDF reader: scroll, pinch, search, select, annotate, split/merge.
`applicationId` `id.larus.pdf`, minSdk 28, Kotlin + Compose + Material 3.
Read the repo-root `CLAUDE.md` for the house style first.

## Build

```
./build.sh              # debug APK
./build.sh test         # unit tests — page-range parsing, transforms
./build.sh install
./build.sh release      # unsigned release APK
```

## The hard rules

- **The app declares no Android permissions at all.** Documents arrive as content URIs from the
  system picker or a share. Do not add a permission, a storage path, or a network call — that
  claim is on the store listing and in the README.
- **The original file is never modified.** It is a content URI the app may not even be allowed to
  write. Annotations live beside the app's data, keyed by document; "Export annotated copy"
  flattens them into a *new* file handed to the share sheet.
- **Rendering goes through `androidx.pdf`**, which runs the renderer in an isolated process, so a
  hostile PDF cannot take the app down. Do not fall back to the platform `PdfRenderer` in-process.
- **minSdk 28 exists for a reason**: text search/selection APIs are `since=35` on the platform
  renderer; `androidx.pdf` backports them to 28. Raising or lowering that floor is a product
  decision, not a cleanup.

## Layout

| Path | What lives there |
|---|---|
| `pdf/PdfSession.kt` | The open document — pages, text, search, selection. |
| `pdf/PageCache.kt` | On-demand page rendering and caching; re-renders at zoom instead of upscaling. |
| `pdf/PageTransform.kt` | Zoom/pan/rotate maths. Pure, unit-tested. |
| `annot/` | Annotation model, on-disk store, and the flattening exporter. |
| `tools/PdfTools.kt` | Split and merge by copying page objects — text, fonts and vector art must survive. |
| `data/Recents.kt` | Recent documents and the page you stopped on. |
| `ui/ViewerScreen.kt`, `ViewerViewModel.kt` | The reader. The big ones. |
| `ui/ToolsScreen.kt`, `ToolsViewModel.kt` | Split/merge UI. |

## Scope

Deliberately not a PDF suite: no OCR, image conversion, scanning, form filling or page reordering.
A feature request in those directions is a "no" unless the owner says otherwise.
