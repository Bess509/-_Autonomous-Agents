# MediX Windows 启动器

适用于 Windows 10/11 和 Windows PowerShell 5.1+。项目路径可以包含中文和空格。

## 启动

双击 `启动-MediX.bat` 会默认进入 DeepSeek live 模式。启动器会检查 Java 21、运行载体、8080、PostgreSQL、Redis 和 Ollama，然后安全读取 API Key：优先继承当前进程的 `MEDIX_OPENAI_API_KEY`，未设置时使用隐藏输入。

- 离线演示：命令行传入 `-Mode Offline`，不需要 API Key。
- DeepSeek live：Key 通过 PowerShell 隐藏输入，只由启动器的 Java/Maven 子进程临时继承；不写入命令行、日志、状态文件或磁盘凭据。

默认推荐 Maven 运行当前源码。若 `target` 中只有一个有效的 `medix-java-*.jar`（不含 `original-*`），可以显式选择 JAR。

PostgreSQL 不可用会阻断启动。Redis 或 Ollama 不可用时，可以确认降级到本地 fallback。启动器显式设置：

```text
MEDIX_RERANKER_ENABLED=false
MEDIX_VECTOR_STORE_ENABLED=false
MEDIX_MINIO_ENABLED=false
```

数据库和 Redis 地址、用户名等非敏感配置优先继承当前进程中的 `MEDIX_*` 环境变量；没有时使用项目的本地开发默认值。`MEDIX_DB_PASSWORD` 与 `MEDIX_REDIS_PASSWORD` 只会继承启动会话已有值，启动器本身没有密码默认值，也不会设置或打印缺失的密码。

## 日志、健康与停止

- 日志：`windows-launcher/logs/`
- 最小状态：`windows-launcher/state/medix-launcher-state.json`，只含 PID、时间、项目路径、载体和模式。
- 健康检查：`http://127.0.0.1:8080/actuator/health`
- **请优先双击 `停止-MediX.bat` 停止 MediX。** 这是受支持并经过自动验证的可靠停止方式。
- 普通前台启动窗口中的 `Ctrl+C` 仅作为便利方式；如果没有立即生效，请改用 `停止-MediX.bat`。

停止脚本会核验 PID 的命令行、项目路径和创建时间。归属不符时只清理陈旧状态，绝不终止未知进程。

## 确定性测试模式

测试模式不访问真实 DeepSeek，也不需要真实 Key。示例：

```powershell
powershell -NoProfile -File .\start-medix.ps1 -Mode Offline -NonInteractive -TestScenario JavaWrong -TestRuntimeRoot "$env:TEMP\medix-test"
powershell -NoProfile -File .\start-medix.ps1 -Mode Live -NonInteractive -TestScenario LiveSentinel -TestRuntimeRoot "$env:TEMP\medix-test" -HealthTimeoutSeconds 10
```

测试替身仅把 `ApiKeyPresent=true/false` 写入脱敏环境探针，不写入 Key 值。`-TestDetach` 仅用于隔离的停止测试。

## 回滚

删除整个 `medix-java/windows-launcher/`，并撤销 `.gitignore` 中 `medix-java/windows-launcher/logs/` 与 `medix-java/windows-launcher/state/` 两条规则即可。启动器不会修改数据库、项目配置或系统执行策略。
