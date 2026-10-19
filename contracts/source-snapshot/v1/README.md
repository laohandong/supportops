# 来源包 v1

包最多128 KiB UTF-8。解析器拒绝重复属性、未知字段和尾随JSON。摘要不证明真实性；接收方明确标记 ADMIN_IMPORTED。

摘要输入为除 contentSha256 外全部字段的规范JSON：对象键按 Unicode 顺序排序；数组保持原序；字符串先将CRLF/CR变为LF、去除首尾ASCII空格/Tab/CR/LF，再做NFC；null保留，整数用十进制无前导零；无额外空白，UTF-8无BOM。仅协议固定整数，不接受浮点数、NaN或Infinity。

example.json 的摘要为 dd77b3ce0a011fe30cb17f59b189f65c2a6ac24e23731cb0b3ed9109fc109202。canonical.txt 末尾换行仅为文件展示，不纳入摘要。旧迁移业务JSON保留原字符串，不能用此规范重算旧摘要。
