#!/usr/bin/env python3
"""Writes a simple one-column text PDF (no dependencies).

Usage: make_changes_pdf.py <input.txt> <output.pdf>

Input format: first line is the title. Lines starting with "## " are headings,
lines starting with "- " are bullets, blank lines separate paragraphs.
Text must be representable in Latin-1.
"""
import sys
import textwrap

PAGE_W, PAGE_H = 595, 842
MARGIN = 56
BODY_SIZE = 11
LEADING = 15
CHARS_PER_LINE = 88


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def layout(lines):
    """Returns pages as lists of (font, size, x, text)."""
    pages, ops = [], []
    y = PAGE_H - MARGIN

    def emit(font, size, x, text, advance):
        nonlocal y, ops
        if y - advance < MARGIN:
            pages.append(ops)
            ops, y = [], PAGE_H - MARGIN
        y -= advance
        ops.append((font, size, x, y, text))

    title, body = lines[0], lines[1:]
    emit("F2", 18, MARGIN, title, 22)
    y -= 8
    para = []

    def flush():
        nonlocal y
        if para:
            for w in textwrap.wrap(" ".join(para), CHARS_PER_LINE):
                emit("F1", BODY_SIZE, MARGIN, w, LEADING)
            para.clear()
            y -= 6

    for raw in body:
        line = raw.rstrip()
        if not line:
            flush()
        elif line.startswith("## "):
            flush()
            y -= 6
            emit("F2", 13, MARGIN, line[3:], 18)
        elif line.startswith("- "):
            flush()
            wrapped = textwrap.wrap(line[2:], CHARS_PER_LINE - 4)
            for i, w in enumerate(wrapped):
                if i == 0:
                    emit("F1", BODY_SIZE, MARGIN, "\x95", LEADING)
                    ops.append(("F1", BODY_SIZE, MARGIN + 14, y, w))
                else:
                    emit("F1", BODY_SIZE, MARGIN + 14, w, LEADING)
            y -= 3
        else:
            para.append(line)
    flush()
    pages.append(ops)
    return pages


def build(pages) -> bytes:
    objs = []

    def add(b: bytes) -> int:
        objs.append(b)
        return len(objs)

    font_regular = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    font_bold = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    pages_id = len(objs) + 1
    add(b"")  # placeholder for /Pages
    page_ids = []
    for ops in pages:
        stream = "".join(
            f"BT /{f} {s} Tf {x} {y} Td ({esc(t)}) Tj ET\n" for f, s, x, y, t in ops
        ).encode("latin-1")
        content = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"endstream")
        page_ids.append(add(
            (f"<< /Type /Page /Parent {pages_id} 0 R /MediaBox [0 0 {PAGE_W} {PAGE_H}] "
             f"/Resources << /Font << /F1 {font_regular} 0 R /F2 {font_bold} 0 R >> >> "
             f"/Contents {content} 0 R >>").encode()
        ))
    kids = " ".join(f"{p} 0 R" for p in page_ids)
    objs[pages_id - 1] = f"<< /Type /Pages /Kids [{kids}] /Count {len(page_ids)} >>".encode()
    catalog = add(f"<< /Type /Catalog /Pages {pages_id} 0 R >>".encode())

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objs, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
        len(objs) + 1, catalog, xref)
    return bytes(out)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding="utf-8") as f:
        lines = f.read().splitlines()
    with open(dst, "wb") as f:
        f.write(build(layout(lines)))


if __name__ == "__main__":
    main()
