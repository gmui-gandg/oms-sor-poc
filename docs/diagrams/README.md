Diagrams for OMS + SOR POC
=========================

This folder contains Mermaid diagram source files for the OMS+SOR POC. Files:
- `architecture.mmd` — high-level component flowchart
- `sequence.mmd` — order lifecycle sequence diagram
- `deployment.mmd` — deployment topology (Kubernetes)

Rendering
- Recommended: install `@mermaid-js/mermaid-cli` (npm package `@mermaid-js/mermaid-cli`) to render SVG/PNG.

Windows PowerShell example to install and render:
```powershell
npm install -g @mermaid-js/mermaid-cli puppeteer
mmdc -i docs/diagrams/architecture.mmd -o docs/diagrams/architecture.svg
mmdc -i docs/diagrams/sequence.mmd -o docs/diagrams/sequence.svg
mmdc -i docs/diagrams/deployment.mmd -o docs/diagrams/deployment.svg
```

Alternatively, view the `.mmd` files in VS Code with the "Markdown Preview Mermaid Support" extension or the "Mermaid Markdown Syntax Highlighting" extension.

Notes
- The `.mmd` files are plain Mermaid source; for production-ready diagrams you may prefer to export optimized SVGs and check them into the repo.
