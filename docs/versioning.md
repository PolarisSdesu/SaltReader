# 版本管理

版本号只有一个来源：仓库根目录的 `version.properties`。`app/build.gradle.kts` 读取它生成
`versionName` / `versionCode`，任何地方都不要手写版本号。

## 规则

- 版本名：语义化版本 `MAJOR.MINOR.PATCH`，预发布追加 `-stage.N`：
  - `alpha`：内部测试（当前阶段）
  - `beta`：功能冻结后的公测
  - `rc`：发布候选
  - `stable`：正式版（不带后缀）
- `versionCode`：单调递增整数，每次 bump 自动 +1，永不回退（Android/Play 覆盖升级要求）。
- 当前值：见 `version.properties`（`python3 scripts/bump_version.py show` 可查看）。

## 命令

```bash
# 查看当前版本
python3 scripts/bump_version.py show

# 日常测试：alpha 序号 +1（1.0.0-alpha.1 -> 1.0.0-alpha.2）
python3 scripts/bump_version.py alpha

# 切换到其他预发布阶段
python3 scripts/bump_version.py beta
python3 scripts/bump_version.py rc

# 发布正式版（1.0.0-alpha.5 -> 1.0.0）
python3 scripts/bump_version.py release

# 下一轮功能：修订 / 次版本 / 主版本，并自动回到 alpha.1
python3 scripts/bump_version.py patch   # 1.0.0 -> 1.0.1-alpha.1
python3 scripts/bump_version.py minor   # 1.0.0 -> 1.1.0-alpha.1
python3 scripts/bump_version.py major   # 1.0.0 -> 2.0.0-alpha.1

# 显式设置（也会 +1 versionCode）
python3 scripts/bump_version.py set 1.2.0-beta.1
```

也可以走 Gradle：

```bash
./gradlew bumpVersion -Paction=alpha
```

## 发版流程

1. `python3 scripts/bump_version.py alpha`（或对应阶段）
2. `./gradlew :app:assembleRelease`
3. 安装验证后提交，并打 tag：`git tag v$(python3 scripts/bump_version.py show | head -1 | cut -d= -f2 | xargs)`
   （脚本执行完也会打印建议的 tag）
4. 上架 Play 时使用 AAB，`versionCode` 已由脚本保证递增。
