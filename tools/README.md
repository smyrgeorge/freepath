# PDF Build Scripts

This folder contains scripts and templates for building PDF documentation from Markdown files.

## Contents

- `github-alerts.lua` - Lua filter to convert GitHub-style alerts (`> [!NOTE]`, etc.) to LaTeX
- `template.tex` - Pandoc LaTeX template

## Building the PDF

Basic command:

```bash
pandoc README.md -o README.pdf --lua-filter=scripts/github-alerts.lua --template=scripts/template.tex
```

### Supported GitHub Alerts

The Lua filter supports these GitHub alert types:

- `> [!NOTE]` - Blue info box
- `> [!TIP]` - Green tip box
- `> [!IMPORTANT]` - Purple important box
- `> [!WARNING]` - Orange warning box
- `> [!CAUTION]` - Red caution box
