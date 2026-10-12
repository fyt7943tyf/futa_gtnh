# GitHub 自动升版与正式发布

正式发布统一使用 [Actions → 发版](https://github.com/fyt7943tyf/futa_gtnh/actions/workflows/release.yml)。
普通代码推送只运行 CI，不修改版本号。迭代构建保持版本不变，确认本轮代码可以正式交付后再触发发版。

## 操作

点击 **Run workflow**，分支选择 **main**：

| 参数 | 行为 | 当前 1.28.2 的正常示例 |
| --- | --- | --- |
| `patch`（默认） | 补丁号加一 | 1.28.3 |
| `minor` | 次版本加一，补丁归零 | 1.29.0 |
| `major` | 主版本加一，其余归零 | 2.0.0 |
| `retry` | 重试当前尚未正式发布的 tag，不再升版本 | 原失败版本 |

保持“仅验证”关闭即可正式发布。打开它时仍会计算新版本、构建、运行检查并保存验证产物，但不会提交版本、创建 tag 或发布 Release。

不需要手动改 `version.txt` 或打 tag。计算新版本时取 `version.txt`、已有正式格式 tags 和 Release 所占版本的最大值，再按选择升版，避免草稿、失败遗留 tag 或已有版本造成冲突。

## 自动流程

1. 确认从当前 main 启动，运行发版规则测试。
2. 自动计算下一个版本，仅修改 Actions 临时工作区中的 `version.txt`。
3. 执行 `gradlew build spotlessCheck`，包含项目现有检查。
4. 校验主 JAR 中 `mcmod.info` 的 mod ID、版本号和入口类，确保 `version.txt`、文件名、tag 与 JAR 一致。
5. 构建通过后，机器人仅提交 `version.txt`；用 Git 原子推送同时更新 main 和创建 tag。
6. 创建 Release 草稿，上传主 JAR 和存在的 sources JAR；核对附件名称、状态、大小和 GitHub 提供的 SHA-256。
7. 所有附件通过核对后才正式发布，标记为 latest。启动前更新器此时才能发现它。

版本写回提交由 `github-actions[bot]` 完成，使用工作流自带的 `GITHUB_TOKEN` 和 `contents: write` 权限，无需额外配置 PAT。机器人写回不触发递归发版。
如果仓库以后禁止机器人直接更新 main，需要调整仓库规则，或将写回流程改为 PR；工作流不会强推绕过规则。

## 失败处理

- **构建失败**：main 版本和 tags 没有变动；修好代码后再次选择 patch/minor/major。
- **构建期间 main 被别人更新**：停止写回，不覆盖其他提交；基于最新 main 重新运行。
- **版本提交和 tag 推送被拒绝**：原子推送保证两者不会只成功一半；修复权限或分支规则后重试。
- **提交与 tag 成功，上传或发布失败**：main 已保留本次版本，选择 `retry`。它重建该 tag 对应的固定提交，不将后续 main 代码混进同一版本。
- **该版本已经发布**：retry 会报错；发布新的 patch/minor/major，不覆盖原附件。
- **已有更新版本发布**：旧草稿不会被发布并取代 latest。

如果失败发生在版本写回之后，不要用旧运行的“Re-run all jobs”升版；使用 main 上的 Run workflow → retry。

交付说明仍可写在 `docs/<目标版本>-改动与交付说明.md`，工作流会合并该文件和 GitHub 生成的发布说明。
未提供该文件时使用 GitHub 自动生成的说明。

部署本工作流时不自动发布新模组；当前 1.28.2 保持不变。下一次选择 patch 正式发布通常得到 1.28.3。
