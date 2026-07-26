package com.sagevault.kb;

import com.ruoyi.common.security.annotation.EnableCustomConfig;
import com.ruoyi.common.security.annotation.EnableRyFeignClients;
import com.sagevault.kb.bootstrap.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.mybatis.spring.annotation.MapperScan;

@EnableCustomConfig
@EnableRyFeignClients
// @EnableConfigurationProperties(RagProperties.class)
@SpringBootApplication
@MapperScan("com.sagevault.kb.**.mapper")
public class KnowledgeBaseManagementApplication {
    public static void main(String[] args) { SpringApplication.run(KnowledgeBaseManagementApplication.class, args); }
}
