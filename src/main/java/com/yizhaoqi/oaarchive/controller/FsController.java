package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * 弹出系统原生的“选择文件夹”窗口（技术文档 6.1.1）。浏览器拿不到本地文件夹的真实路径，只能由后端来选。
 */
@RestController
@RequestMapping("/api/fs")
public class FsController {

    private final OaProperties props;

    public FsController(OaProperties props) {
        this.props = props;
    }

    @PostMapping("/choose-folder")
    public ApiResponse<Map<String, String>> chooseFolder(@RequestParam(required = false) String initial,
                                                         @RequestParam(defaultValue = "选择文件夹") String title) throws Exception {
        if (props.isDocker() || GraphicsEnvironment.isHeadless()) {
            throw new BizException("当前模式不能弹出选择窗口，请直接填写路径");
        }
        AtomicReference<String> chosen = new AtomicReference<>();
        // 窗口在 Swing 线程里打开并置顶，否则会被浏览器挡住
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 用默认外观
            }
            JFileChooser chooser = new JFileChooser(initial == null || initial.isBlank() ? null : new File(initial));
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(title);
            JDialog owner = new JDialog();
            owner.setAlwaysOnTop(true);
            owner.setLocationRelativeTo(null);
            try {
                if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                    chosen.set(chooser.getSelectedFile().getAbsolutePath());
                }
            } finally {
                owner.dispose();
            }
        });
        return ApiResponse.ok(chosen.get() == null ? Map.of() : Map.of("path", chosen.get()));
    }
}
