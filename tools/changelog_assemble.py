"""Validate and assemble Keep a Changelog fragments."""

import argparse
import os
import re
import sys
import tempfile
from pathlib import Path


TYPES = ("Added", "Changed", "Fixed")
NAME = re.compile(r"([A-Za-z0-9][A-Za-z0-9_-]*)\.((?i:added|changed|fixed))\.md\Z", re.ASCII)


def natural_key(identifier):
    """Compare numeric chunks as integers and put numeric ids before slugs."""
    return tuple((0, int(part)) if part.isdigit() else (1, part.casefold())
                 for part in re.findall(r"\d+|\D+", identifier))


def line_text(line):
    return line.rstrip("\r\n")


def error(filename, message):
    safe_name = filename.encode("ascii", "backslashreplace").decode("ascii")
    safe_message = message.encode("ascii", "backslashreplace").decode("ascii")
    return f"changelog: {safe_name}: {safe_message}"


def read_fragments(directory):
    grouped = {kind: [] for kind in TYPES}
    errors = []
    if not directory.exists():
        return grouped, errors
    try:
        children = sorted(directory.iterdir(), key=lambda path: path.name)
    except OSError as exc:
        return grouped, [error("changelog.d", str(exc))]
    for path in children:
        if path.name == "README.md" or path.name.startswith("."):
            continue
        if path.is_symlink():
            errors.append(error(path.name, "symbolic links are not allowed"))
            continue
        if path.is_dir():
            continue
        match = NAME.fullmatch(path.name)
        if not match:
            errors.append(error(path.name, "invalid fragment filename"))
            continue
        try:
            raw = path.read_bytes()
        except OSError as exc:
            errors.append(error(path.name, str(exc)))
            continue
        if raw.startswith(b"\xef\xbb\xbf"):
            errors.append(error(path.name, "UTF-8 BOM is not allowed"))
            continue
        try:
            lines = raw.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n").split("\n")
        except UnicodeDecodeError:
            errors.append(error(path.name, "invalid UTF-8"))
            continue
        nonblank = [line for line in lines if line.strip()]
        if not nonblank:
            errors.append(error(path.name, "empty fragment"))
            continue
        if any(line.startswith("#") for line in lines):
            errors.append(error(path.name, "headings are not allowed"))
            continue
        if not nonblank[0].startswith("- "):
            errors.append(error(path.name, "first non-blank line must be a bullet"))
            continue
        if any(line.strip() and not (line.startswith("- ") or line.startswith("  ")) for line in lines):
            errors.append(error(path.name, "invalid continuation indentation"))
            continue
        while not lines[0].strip():
            lines.pop(0)
        while not lines[-1].strip():
            lines.pop()
        kind = match.group(2).capitalize()
        grouped[kind].append((match.group(1), path, "\n".join(line.rstrip() for line in lines)))
    for items in grouped.values():
        items.sort(key=lambda item: (natural_key(item[0]), item[1].name))
    return grouped, errors


def insert_lines(lines, index, additions, newline, no_final_newline):
    if index == len(lines) and lines and not lines[-1].endswith(("\n", "\r")):
        lines[-1] += newline
    new_lines = [line + newline for line in additions]
    if index == len(lines) and no_final_newline:
        new_lines[-1] = line_text(new_lines[-1])
    lines[index:index] = new_lines


def assemble(changelog, grouped):
    newline = "\r\n" if "\r\n" in changelog else "\n"
    no_final_newline = not changelog.endswith(("\n", "\r"))
    lines = changelog.splitlines(keepends=True)
    for kind in TYPES:
        if not grouped[kind]:
            continue
        start = next(i for i, line in enumerate(lines) if line_text(line) == "## [Unreleased]")
        end = next((i for i in range(start + 1, len(lines)) if line_text(lines[i]).startswith("## ")), len(lines))
        headings = [(i, line_text(lines[i])[4:]) for i in range(start + 1, end)
                    if line_text(lines[i]).startswith("### ")]
        entry_lines = "\n".join(item[2] for item in grouped[kind]).split("\n")
        matching = next((i for i, title in headings if title == kind), None)
        if matching is not None:
            boundary = next((i for i, _ in headings if i > matching), end)
            last = max((i for i in range(matching, boundary) if line_text(lines[i]).strip()), default=matching)
            index = last + 1
            additions = ([""] if last == matching and line_text(lines[matching]) == "### " + kind else []) + entry_lines
            if index < len(lines) and line_text(lines[index]).strip() and index == boundary:
                additions.append("")
        else:
            rank = TYPES.index(kind)
            lower = [i for i, title in headings if title in TYPES and TYPES.index(title) < rank]
            higher = [i for i, title in headings if title in TYPES and TYPES.index(title) > rank]
            if higher and not lower:
                index = min(higher)
            else:
                # After the last lower-ranked canonical subsection, otherwise at the block end.
                first = max(lower) if lower else start
                stop = next((i for i, _ in headings if i > first), end)
                index = max((i for i in range(first, stop) if line_text(lines[i]).strip()), default=first) + 1
            additions = ([] if index and not line_text(lines[index - 1]).strip() else [""])
            additions += ["### " + kind, ""] + entry_lines
            if (index < len(lines) and line_text(lines[index]).strip()) or (index == len(lines) and not no_final_newline):
                additions.append("")
        insert_lines(lines, index, additions, newline, no_final_newline)
    return "".join(lines)


def preview(grouped):
    return "".join("### " + kind + "\n\n" + "\n".join(item[2] for item in grouped[kind]) + "\n\n"
                   for kind in TYPES if grouped[kind])


def main(argv=None):
    parser = argparse.ArgumentParser(description="Assemble changelog fragments")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)

    grouped, errors = read_fragments(args.root / "changelog.d")
    changelog_path = args.root / "CHANGELOG.md"
    try:
        changelog = changelog_path.read_bytes().decode("utf-8")
    except FileNotFoundError:
        errors.append(error("CHANGELOG.md", "file is missing"))
        changelog = None
    except UnicodeDecodeError:
        errors.append(error("CHANGELOG.md", "invalid UTF-8"))
        changelog = None
    except OSError as exc:
        errors.append(error("CHANGELOG.md", str(exc)))
        changelog = None
    if changelog is not None:
        if not any(line_text(line) == "## [Unreleased]" for line in changelog.splitlines()):
            errors.append(error("CHANGELOG.md", "missing ## [Unreleased]"))
        elif not errors:
            assembled = assemble(changelog, grouped)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1

    count = sum(map(len, grouped.values()))
    if args.check:
        print(f"changelog: {count} fragment(s) OK")
    elif args.apply and count:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="", dir=args.root,
                                             prefix=".changelog-", delete=False) as handle:
                temporary = Path(handle.name)
                handle.write(assembled)
            os.replace(temporary, changelog_path)
        except OSError as exc:
            print(error("CHANGELOG.md", str(exc)), file=sys.stderr)
            return 1
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        stuck = []
        for items in grouped.values():
            for _, path, _ in items:
                try:
                    path.unlink()
                except OSError as exc:
                    stuck.append(error(path.name, f"inserted but not deleted, remove it by hand: {exc}"))
        if stuck:
            print("\n".join(stuck), file=sys.stderr)
            return 1
        print(f"changelog: inserted {count} fragment(s)")
    elif not args.apply:
        print(preview(grouped), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
