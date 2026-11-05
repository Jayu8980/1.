package com.hft.piano2midi;

import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import com.hft.piano2midi.core.sheet.SheetLayout;

import java.io.IOException;
import java.io.OutputStream;

/** Writes an engraved score to a multi-page A4 PDF using the same renderer as the screen. */
final class PdfExporter {
    private PdfExporter() {}

    static void write(SheetLayout layout, Typeface music, OutputStream os) throws IOException {
        PdfDocument doc = new PdfDocument();
        try {
            for (int p = 0; p < layout.pageCount(); p++) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                        Math.round(layout.pageW), Math.round(layout.pageH), p + 1).create();
                PdfDocument.Page page = doc.startPage(info);
                layout.drawPage(new AndroidGfx(page.getCanvas(), music), p);
                doc.finishPage(page);
            }
            doc.writeTo(os);
        } finally {
            doc.close();
        }
    }
}
