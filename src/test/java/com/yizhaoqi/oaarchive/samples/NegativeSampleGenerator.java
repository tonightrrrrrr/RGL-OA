package com.yizhaoqi.oaarchive.samples;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 生成“反面案例”示例文件：故意让识别、解析、去重、过滤出各种问题，演示程序遇到失败情况时的表现。
 * 运行一次即可（生成的文件随代码提交）：
 * <pre>java -cp "target/classes;target/test-classes;依赖" com.yizhaoqi.oaarchive.samples.NegativeSampleGenerator</pre>
 * 每个文件的预期结果和说明见同目录的 cases.json，NegativeSamplesTest 会逐个检查。
 */
public final class NegativeSampleGenerator {

    static final Path OUT = Path.of("src/main/resources/samples-negative");
    private static final String FONT = "C:/Windows/Fonts/simhei.ttf";

    private NegativeSampleGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        try (var s = Files.list(OUT)) {
            for (Path p : s.toList()) {
                if (!p.getFileName().toString().equals("cases.json")) {
                    Files.delete(p);
                }
            }
        }

        // —— 待确认：识别结果不可靠，需要人工确认 ——
        docx("扫描件001.docx", "关于加强办公秩序管理的通知",
                List.of("各位同事：", "近期部分区域存在随意摆放物品、下班不关灯等现象，请大家自觉遵守办公秩序。",
                        "请各自整理工位，保持公共区域整洁。", "特此通知。"),
                List.of("2026年3月10日"));
        docx("新建 Microsoft Word 文档.docx", "关于开展2026年度联合检查工作的通知",
                List.of("为落实公司年度工作安排，现组织开展联合检查。",
                        "财务部负责检查各部门预算执行、费用报销和资产台账情况。",
                        "采购部负责检查采购合同、供应商准入和招标流程执行情况。",
                        "财务部、采购部于4月30日前提交检查报告。"),
                List.of("2026年4月8日"));
        docx("会议纪要_待整理.docx", "项目推进会会议纪要",
                List.of("会议时间：本周三上午", "参会人员：项目组全体成员",
                        "一、会议听取了项目进展汇报。", "二、会议要求各成员按计划推进，下次会议再议。"),
                List.of());
        docx("备忘录.docx", "备忘",
                List.of("记得周五前把材料交给王经理。", "下周一上午九点开会，带上笔记本电脑。"),
                List.of());
        blankImage("手机拍照_IMG_2045.png");

        // —— 处理失败：文件本身有问题 ——
        Files.write(OUT.resolve("损坏的合同_2026-01-15.docx"),
                ("PK\u0003\u0004 这不是一个完整的 Word 文件，只有开头几个字节，模拟下载中断或磁盘损坏的文件。")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(OUT.resolve("损坏的报表_2026-02-28.xlsx"), new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00,
                0x08, 0x00, 0x00, 0x00, 0x21, 0x00, 0x12, 0x34, 0x56, 0x78});
        encryptedPdf("财务部_加密的薪酬明细_2026-03-31.pdf");

        // —— 重复：和台账里已归档的文件内容一样（先归档 10 份成功示例再导入），或者同一批里有两份一样的 ——
        Files.copy(Path.of("src/test/resources/samples/A/法务部_原材料采购框架合同_2025-10-01.pdf"),
                OUT.resolve("原材料采购框架合同（重复上传的副本）.pdf"));
        docx("销售部_客户拜访记录表_2026-04-15.docx", "2026年4月客户拜访记录汇总表",
                List.of("拜访客户：华东区重点客户三家。", "拜访内容：介绍新一代智能检测设备，收集客户需求。"),
                List.of("销售部", "2026年4月15日"));
        Files.copy(OUT.resolve("销售部_客户拜访记录表_2026-04-15.docx"), OUT.resolve("销售部_客户拜访记录表_2026-04-15 - 副本.docx"));

        // —— 重名：两份文件识别结果完全相同，归档时第二份自动加序号 ——
        for (String n : List.of("行政部_会议室使用管理规定_2026-05-06（一）.docx", "行政部_会议室使用管理规定_2026-05-06（二）.docx")) {
            String version = n.contains("（一）") ? "征求意见稿" : "正式稿";
            docx(n, "会议室使用管理规定",
                    List.of("为规范会议室使用，制定本规定（" + version + "）。",
                            "一、会议室需提前在OA系统预约，使用后恢复桌椅摆放。", "二、本规定由行政部负责解释。"),
                    List.of("行政部", "2026年5月6日"));
        }

        // —— 能归档，但文件名需要处理 ——
        docx("研发部_规划报告_2026-06-01.docx", "研发部2026年下半年技术规划：AI/大数据方向*重点*项目?汇总<征求意见>",
                List.of("一、AI方向：继续推进智能检测算法迭代。", "二、大数据方向：完成数据中台一期建设。",
                        "三、请各项目组于6月15日前反馈意见。"),
                List.of("研发部", "2026年6月1日"));

        // —— 导入时被跳过 ——
        Files.write(OUT.resolve("~$临时文件.docx"), "Word 打开文件时生成的锁文件".getBytes(StandardCharsets.UTF_8));
        Files.createFile(OUT.resolve("空白文件.txt"));

        System.out.println("已生成反面案例：" + OUT.toAbsolutePath());
    }

    /** 标题用 22 磅黑体（程序靠字号找标题），正文 14 磅，落款右对齐 */
    static void docx(String name, String title, List<String> body, List<String> signature) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(OUT.resolve(name))) {
            XWPFParagraph t = doc.createParagraph();
            t.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = t.createRun();
            tr.setText(title);
            tr.setBold(true);
            tr.setFontSize(22);
            tr.setFontFamily("黑体");
            for (String line : body) {
                XWPFRun r = doc.createParagraph().createRun();
                r.setText(line);
                r.setFontSize(14);
                r.setFontFamily("仿宋");
            }
            for (String line : signature) {
                XWPFParagraph p = doc.createParagraph();
                p.setAlignment(ParagraphAlignment.RIGHT);
                XWPFRun r = p.createRun();
                r.setText(line);
                r.setFontSize(14);
            }
            doc.write(out);
        }
    }

    /** 设置了打开密码的 PDF（密码 123456） */
    static void encryptedPdf(String name) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, new File(FONT));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 20);
                cs.newLineAtOffset(150, 760);
                cs.showText("2026年3月薪酬明细（保密）");
                cs.setFont(font, 12);
                cs.newLineAtOffset(-80, -40);
                cs.showText("财务部  2026年3月31日");
                cs.endText();
            }
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "123456", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(OUT.resolve(name).toFile());
        }
    }

    /** 拍糊了、几乎没有文字的照片：OCR 读不出内容 */
    static void blankImage(String name) throws IOException {
        BufferedImage img = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(236, 232, 224));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(new Color(214, 208, 198));
        g.fillOval(300, 500, 600, 420); // 一块模糊的阴影，没有文字
        g.dispose();
        ImageIO.write(img, "png", OUT.resolve(name).toFile());
    }
}
