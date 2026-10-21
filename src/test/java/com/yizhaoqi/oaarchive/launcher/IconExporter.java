package com.yizhaoqi.oaarchive.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 生成 packaging/app.ico（内嵌 PNG 的多尺寸 ICO）。图标改动后运行一次即可：
 * mvn -q test-compile exec 或在 IDE 中直接运行 main。
 */
public final class IconExporter {

    public static void main(String[] args) throws IOException {
        // 16–32 是只有脸的版本（资源管理器小图标、任务栏），48 及以上带 RGL（大图标、桌面）
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(TrayManager.createIcon(s), "png", out);
            pngs.add(out.toByteArray());
        }
        int headerSize = 6 + 16 * sizes.length;
        int total = headerSize + pngs.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)); // 256 用 0 表示
            buf.put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        pngs.forEach(buf::put);
        Path target = Path.of(args.length > 0 ? args[0] : "packaging/app.ico");
        Files.write(target, buf.array());
        System.out.println("已生成 " + target.toAbsolutePath() + "（" + total + " 字节）");
    }
}
