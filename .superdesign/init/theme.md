# Theme

## Compact token summary

The repository has no application design system. The standalone executive
summary provides the only confirmed visual cues:

- Font stack: `"Segoe UI", Arial, sans-serif`.
- Primary blue: `#1f4e79`.
- Text: `#1a1a1a`; secondary text: `#555` / `#666`.
- Borders: `#b9c6d4` and `#cfd8e3`.
- Light surfaces: `#f7fafc`, table header `#eaf0f6`.
- Compact, information-dense spacing; square report surfaces with minimal
  decoration.
- No dark theme, application breakpoints, motion, shadows, or reusable tokens
  currently exist.

These cues may seed the new product design, but the print document is not an
application UI source of truth.

## Raw source

Source: `docs/exec-summary-lt-verdict.html` embedded stylesheet.

```css
@page { size: A4; margin: 13mm 14mm; }
* { box-sizing: border-box; }
body { font-family: "Segoe UI", Arial, sans-serif; font-size: 9pt; line-height: 1.27; color: #1a1a1a; margin: 0; }
.header { border-bottom: 3px solid #1f4e79; padding-bottom: 4px; margin-bottom: 7px; }
h1 { font-size: 17pt; color: #1f4e79; margin: 0 0 2px 0; }
.subtitle { color: #555; font-size: 9pt; }
h2 { font-size: 10pt; color: #1f4e79; margin: 7px 0 2px 0; text-transform: uppercase; letter-spacing: .4px; border-bottom: 1px solid #cfd8e3; padding-bottom: 2px; }
p { margin: 3px 0; }
ul { margin: 3px 0 3px 16px; padding: 0; }
li { margin: 1px 0; }
table { border-collapse: collapse; width: 100%; margin: 4px 0; }
th, td { border: 1px solid #b9c6d4; padding: 2px 5px; text-align: left; vertical-align: top; }
th { background: #eaf0f6; font-weight: 600; }
.kpi { display: flex; gap: 8px; margin: 6px 0; }
.kpi div { flex: 1; border: 1px solid #cfd8e3; border-left: 3px solid #1f4e79; padding: 5px 8px; background: #f7fafc; }
.kpi b { display: block; font-size: 11.5pt; color: #1f4e79; }
.note { color: #555; font-size: 8.6pt; }
.footer { margin-top: 6px; padding-top: 4px; border-top: 1px solid #cfd8e3; color: #666; font-size: 8.2pt; }
```
