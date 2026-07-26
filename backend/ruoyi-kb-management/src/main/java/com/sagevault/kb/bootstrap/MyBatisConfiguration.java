package com.sagevault.kb.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.sagevault.kb.**.mapper")
public class MyBatisConfiguration { }
