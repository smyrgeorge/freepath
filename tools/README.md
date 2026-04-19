# PDF Build Scripts

This folder contains scripts and templates for building PDF documentation from Markdown files.

## Contents

- `github-alerts.lua` - Lua filter to convert GitHub-style alerts (`> [!NOTE]`, etc.) to LaTeX
- `render-emoji.lua` - Lua filter that wraps emoji / pictographic characters in `{\emojifont ...}` so they are typeset
  with an emoji-capable font instead of the default text font. Ballot-box chars (`☐ ☑ ☒`) are mapped to LaTeX math
  symbols (`\square \boxdot \boxtimes`) since Noto Color Emoji does not cover them.
- `template.tex` - Pandoc LaTeX template. Declares `\emojifont` via `fontspec`, falling back from Noto Emoji
  (monochrome, preferred) → Symbola → Noto Color Emoji → no-op.

## Prerequisites

- [`pandoc`](https://pandoc.org/)
- [`tectonic`](https://tectonic-typesetting.github.io/) as the PDF engine (auto-fetches LaTeX packages on demand)
- [`mermaid-filter`](https://github.com/raghur/mermaid-filter) to render Mermaid code blocks into images (requires Node)
- An emoji font that XeTeX can embed. The tested path is **Noto Emoji** (monochrome). XeTeX + `xdvipdfmx` cannot embed
  color emoji tables reliably (COLR/sbix) — color fonts load but produce blank glyphs in the PDF. Apple Color Emoji is
  also unsupported.

On macOS:

```bash
brew install pandoc tectonic node
brew install --cask font-noto-emoji
npm install -g mermaid-filter
```

## Building the PDF

```bash
pandoc README.md -o README.pdf \
  --filter=mermaid-filter \
  --lua-filter=tools/github-alerts.lua \
  --lua-filter=tools/render-emoji.lua \
  --template=tools/template.tex \
  --pdf-engine=tectonic
```

The filter order matters: `mermaid-filter` rasterises diagrams first, `github-alerts.lua` rewrites alert blocks, and
`render-emoji.lua` wraps pictographs for the emoji font.

`mermaid-filter` drops an empty `mermaid-filter.err` file in the working directory on success; it is listed in
`.gitignore`.

### Supported GitHub Alerts

The Lua filter supports these GitHub alert types:

- `> [!NOTE]` - Blue info box
- `> [!TIP]` - Green tip box
- `> [!IMPORTANT]` - Purple important box
- `> [!WARNING]` - Orange warning box
- `> [!CAUTION]` - Red caution box
