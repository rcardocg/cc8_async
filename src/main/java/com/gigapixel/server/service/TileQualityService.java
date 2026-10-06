package com.gigapixel.server.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

@Service
public final class TileQualityService {

    private static final int PREVIEW_SIZE = 64;
    private static final int MAX_PALETTE_COLORS = 256;

    public byte[] generateQuality0(byte[] nativeTile) throws IOException {
        BufferedImage source = decodePng(nativeTile);
        BufferedImage scaled = scaleNearestNeighbor(source, PREVIEW_SIZE, PREVIEW_SIZE);
        BufferedImage quantized = quantizeToPalette(scaled, 256);
        return encodePng(quantized);
    }

    public byte[] generateQuality1(byte[] nativeTile) throws IOException {
        return encodeJpeg(nativeTile, 0.50f);
    }

    public byte[] generateQuality2(byte[] nativeTile) throws IOException {
        return encodeJpeg(nativeTile, 0.85f);
    }

    public byte[] generateQuality3(byte[] nativeTile) throws IOException {
        return nativeTile;
    }

    private BufferedImage decodePng(byte[] pngBytes) throws IOException {
        try (var in = new ByteArrayInputStream(pngBytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) throw new IOException("No se pudo decodificar tile PNG");
            return image;
        }
    }

    private BufferedImage scaleNearestNeighbor(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return target;
    }

    private BufferedImage quantizeToPalette(BufferedImage source, int maxColors) {
        int w = source.getWidth();
        int h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getRGB(0, 0, w, h, pixels, 0, w);

        List<Color> palette = buildPalette(pixels, maxColors);
        byte[] indices = new byte[w * h];
        for (int i = 0; i < pixels.length; i++) {
            indices[i] = (byte) findNearestPaletteIndex(pixels[i], palette);
        }

        byte[] r = new byte[palette.size()];
        byte[] g = new byte[palette.size()];
        byte[] b = new byte[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            r[i] = (byte) palette.get(i).getRed();
            g[i] = (byte) palette.get(i).getGreen();
            b[i] = (byte) palette.get(i).getBlue();
        }

        IndexColorModel icm = new IndexColorModel(8, palette.size(), r, g, b);
        BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, icm);
        indexed.getRaster().setDataElements(0, 0, w, h, indices);
        return indexed;
    }

    private List<Color> buildPalette(int[] pixels, int maxColors) {
        int[] histogram = new int[0x1000000];
        for (int p : pixels) {
            int rgb = p & 0xFFFFFF;
            histogram[rgb]++;
        }

        return java.util.stream.IntStream.range(0, histogram.length)
                .filter(i -> histogram[i] > 0)
                .boxed()
                .sorted((a, b) -> Integer.compare(histogram[b], histogram[a]))
                .limit(maxColors)
                .map(i -> new Color(i))
                .toList();
    }

    private int findNearestPaletteIndex(int pixel, List<Color> palette) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < palette.size(); i++) {
            Color c = palette.get(i);
            int dr = r - c.getRed();
            int dg = g - c.getGreen();
            int db = b - c.getBlue();
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("Encoder PNG no disponible");
            return out.toByteArray();
        }
    }

    private byte[] encodeJpeg(byte[] pngBytes, float quality) throws IOException {
        BufferedImage source = decodePng(pngBytes);
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(source, 0, 0, Color.WHITE, null);
        g.dispose();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("Encoder JPEG no disponible");
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (var out = new ByteArrayOutputStream();
                 var stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
                return out.toByteArray();
            }
        } finally {
            writer.dispose();
        }
    }
}