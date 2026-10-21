package com.yizhaoqi.oaarchive;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot 应用配置入口。程序真正的 main 方法在 {@link com.yizhaoqi.oaarchive.launcher.Launcher}，
 * 它负责在 Spring 启动前确定数据目录、端口和单实例。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OaArchiveApplication {
}
