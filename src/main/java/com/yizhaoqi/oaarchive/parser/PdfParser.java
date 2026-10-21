package com.yizhaoqi.oaarchive.parser;

import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.ocr.OcrEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 解析：先读文字层；某页文字少于阈值且含图片时，判断为扫描页，渲染成图片走 OCR（技术文档 6.3.3）。
 */
@Component
public class PdfParser implements DocumentParser {

    private final OcrEngine ocr;
    private final OaProperties props;

    public PdfParser(OcrEngine ocr, OaProperties props) {
        this.ocr = ocr;
        this.props = props;
    }

    @Override
    public boolean supports(String ext) {
        return "pdf".equals(ext);
    }

    @Override
    public DocModel parse(Path file, String originalName) throws Exception {
        PDDocument doc;
        try {
            doc = PDDocument.load(file.toFile());
        } catch (InvalidPasswordException e) {
            throw new BizException("PDF 设置了打开密码，无法读取");
        }
        try (doc) {
            PdfLineStripper stripper = new PdfLineStripper();
            List<Block> textBlocks = stripper.extract(doc);
            List<Integer> chars = stripper.charsPerPage();
            List<String> notes = new ArrayList<>();
            List<Block> result = new ArrayList<>();
            boolean ocrUsed = false;
            PDFRenderer renderer = null;

            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                int pageNo = i + 1;
                int pageChars = i < chars.size() ? chars.get(i) : 0;
                boolean scanned = pageChars < props.getOcr().getMinTextChars() && hasImage(doc.getPage(i).getResources(), 0);
                if (scanned && ocr.available()) {
                    if (renderer == null) {
                        renderer = new PDFRenderer(doc);
                    }
                    BufferedImage img = renderer.renderImageWithDPI(i, props.getOcr().getRenderDpi(), ImageType.RGB);
                    result.addAll(ocr.recognize(img, pageNo));
                    ocrUsed = true;
                    notes.add("第 " + pageNo + " 页为扫描页，已使用 OCR");
                } else {
                    if (scanned) {
                        notes.add("第 " + pageNo + " 页疑似扫描页，但 OCR 不可用");
                    }
                    for (Block b : textBlocks) {
                        if (b.page() == pageNo) {
                            result.add(b);
                        }
                    }
                }
            }
            Map<String, String> meta = new LinkedHashMap<>();
            var info = doc.getDocumentInformation();
            if (info.getAuthor() != null) {
                meta.put("author", info.getAuthor());
            }
            if (info.getCreationDate() != null) {
                meta.put("created", info.getCreationDate().toInstant().toString());
            }
            if (info.getCreator() != null) {
                meta.put("creatorTool", info.getCreator());
            }
            return new DocModel(originalName, "pdf", doc.getNumberOfPages(), result, meta, ocrUsed, notes);
        }
    }

    /** 页面资源里是否有图片（含嵌套在表单对象里的图片） */
    private static boolean hasImage(PDResources res, int depth) throws IOException {
        if (res == null || depth > 3) {
            return false;
        }
        for (COSName name : res.getXObjectNames()) {
            PDXObject x = res.getXObject(name);
            if (x instanceof PDImageXObject) {
                return true;
            }
            if (x instanceof PDFormXObject form && hasImage(form.getResources(), depth + 1)) {
                return true;
            }
        }
        return false;
    }
}
