# IcarLyrics 签名密钥（侧载分发用，非上架密钥）
#
# 为什么进仓库：GitHub Actions 每次跑都是一台新机器，系统 debug keystore
# 每次都不一样 → 同一版本号两次构建签名不同，用户覆盖安装报「签名不一致」。
# 固定这把密钥后，任何一次 CI / 本地构建签名相同。
#
# 密码：icarlyrics  别名：icarlyrics
# 请勿把此密钥用于其他应用或上传 Play。
