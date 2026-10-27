package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.Block;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期识别（技术文档 6.5.3）：落款日期 > 抬头锚点日期 > 文件名日期 > 其他。
 * 正文里“至”“前”“定于”等附近的日期多半是干扰项（放假时间、下次会议），权重乘以 0.3。
 */
final class DateRecognizer {

    static final Pattern DATE = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})(?!\\d)\\s*日?");
    private static final Pattern COMPACT = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(\\d{2})(\\d{2})(?!\\d)");
    private static final Pattern YEAR_MONTH = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})\\s*年\\s*(\\d{1,2})\\s*月(?!\\s*\\d)");
    private static final Pattern NOISE = Pattern.compile("至|起|止|前|截至|截止|下次|定于|有效期|放假|届满|之后|以后|期间|～|~");
    private static final String[] HEADER_LABELS = {"签订日期", "编制日期", "发文日期", "印发日期", "会议日期", "制表日期",
            "成文日期", "签发日期", "报告日期", "填报日期", "申请日期", "日期", "填表日期", "投递日期", "应聘日期"};
    /** 简历里这些行的日期是出生、毕业、工作经历，不是文件日期 */
    private static final Pattern RESUME_NOISE = Pattern.compile("出生|生日|毕业|入学|入职|离职|到岗|在职|工作|教育|实习|项目|经历|年龄");
    private static final String[] MEETING_LABELS = {"会议时间", "时间"};

    private DateRecognizer() {
    }

    static FieldResult recognize(RecognitionContext ctx) {
        if (ctx.resume().resume()) {
            return resume(ctx);
        }
        ScoreBoard sb = new ScoreBoard();
        int maxYear = LocalDate.now().getYear() + 1;

        // 1. 落款区：“日期：”行权重最高；单独一行的成文日期（公文落款写法）次之；其他日期再次
        for (Block b : ctx.regions().footer()) {
            boolean labeled = b.text().contains("日期：") || b.text().contains("日期:");
            boolean alone = !labeled && RegionSplitter.DATE_LINE.matcher(b.text()).find();
            for (LocalDate d : dates(b.text(), maxYear)) {
                sb.add(d.toString(), labeled ? "FOOTER_DATE" : alone ? "FOOTER_SIGN_DATE" : "FOOTER_OTHER", b.text(),
                        labeled ? 0.90 : alone ? 0.85 : 0.60);
            }
        }
        // 2. 抬头锚点
        for (Anchors.Anchor a : ctx.anchors().withLabel(HEADER_LABELS)) {
            if (a.zone() == Anchors.Zone.FOOTER) {
                continue; // 已在落款区处理
            }
            for (LocalDate d : dates(a.value(), maxYear)) {
                sb.add(d.toString(), "HEADER_ANCHOR", a.raw(), 0.85);
            }
        }
        for (Anchors.Anchor a : ctx.anchors().withLabel(MEETING_LABELS)) {
            for (LocalDate d : dates(a.value(), maxYear)) {
                sb.add(d.toString(), "MEETING_TIME", a.raw(), 0.80);
            }
        }
        // 3. 文件名
        for (String s : ctx.fileName().dates()) {
            for (LocalDate d : dates(s, maxYear)) {
                sb.add(d.toString(), "FILENAME", ctx.fileName().original(), 0.80);
            }
        }
        // 4. 标题里的“2026年3月”：补为当月最后一天，标记为推断
        Matcher ym = YEAR_MONTH.matcher(ctx.regions().titleText() + " " + ctx.fileName().base());
        while (ym.find()) {
            try {
                LocalDate d = YearMonth.of(Integer.parseInt(ym.group(1)), Integer.parseInt(ym.group(2))).atEndOfMonth();
                if (d.getYear() <= maxYear) {
                    sb.add(d.toString(), "TITLE_YEAR_MONTH", ym.group() + "（推断为月末）", 0.40);
                }
            } catch (DateTimeException ignored) {
                // 月份不合法
            }
        }
        // 5. 正文其他日期：权重低，附近有干扰词再打折
        for (Block b : ctx.regions().body()) {
            Matcher m = DATE.matcher(b.text());
            while (m.find()) {
                LocalDate d = toDate(m.group(1), m.group(2), m.group(3), maxYear);
                if (d == null) {
                    continue;
                }
                String around = window(b.text(), m.start(), m.end(), 4);
                double w = NOISE.matcher(around).find() ? 0.075 : 0.25;
                sb.add(d.toString(), "BODY_DATE", around, w);
            }
        }
        return sb.result(null);
    }

    /**
     * 简历的日期（v1.8）：只认写明的“投递日期/填表日期/日期”和文件名里的日期。
     * 正文里的日期多半是出生、毕业、工作经历，不能当文件日期；都没有时用导入当天，引擎会提示确认。
     */
    private static FieldResult resume(RecognitionContext ctx) {
        ScoreBoard sb = new ScoreBoard();
        int maxYear = LocalDate.now().getYear() + 1;
        for (Anchors.Anchor a : ctx.anchors().withLabel(HEADER_LABELS)) {
            if (RESUME_NOISE.matcher(a.label()).find()) {
                continue;
            }
            for (LocalDate d : dates(a.value(), maxYear)) {
                sb.add(d.toString(), "HEADER_ANCHOR", a.raw(), 0.85);
            }
        }
        for (String s : ctx.fileName().dates()) {
            for (LocalDate d : dates(s, maxYear)) {
                sb.add(d.toString(), "FILENAME", ctx.fileName().original(), 0.80);
            }
        }
        if (sb.isEmpty()) {
            return FieldResult.single(LocalDate.now().toString(), 0.30, "IMPORT_DATE", "简历里没有投递日期，用导入当天");
        }
        return sb.result(null);
    }

    static List<LocalDate> dates(String text, int maxYear) {
        if (text == null) {
            return List.of();
        }
        java.util.ArrayList<LocalDate> out = new java.util.ArrayList<>();
        Matcher m = DATE.matcher(text);
        while (m.find()) {
            LocalDate d = toDate(m.group(1), m.group(2), m.group(3), maxYear);
            if (d != null) {
                out.add(d);
            }
        }
        Matcher c = COMPACT.matcher(text);
        while (c.find()) {
            LocalDate d = toDate(c.group(1), c.group(2), c.group(3), maxYear);
            if (d != null && !out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    private static LocalDate toDate(String y, String mo, String d, int maxYear) {
        try {
            int year = Integer.parseInt(y);
            if (year < 1990 || year > maxYear) {
                return null;
            }
            return LocalDate.of(year, Integer.parseInt(mo), Integer.parseInt(d));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static String window(String text, int start, int end, int n) {
        return text.substring(Math.max(0, start - n), Math.min(text.length(), end + n));
    }
}
