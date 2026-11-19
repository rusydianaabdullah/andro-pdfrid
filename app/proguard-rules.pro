# Nothing reflective in this app; the defaults are enough.

# PDFBox reaches for an optional JPEG 2000 codec that this app does not bundle. JPX images are
# rare in PDFs and only matter to PDFBox's own renderer, which we never use — split and merge
# copy page objects without decoding them.
-dontwarn com.gemalto.jp2.**
