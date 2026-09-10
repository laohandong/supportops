# 本地前端依赖

以下文件随应用提供，浏览器不从外部 CDN 加载；原作者的许可声明与源文件一并保留。

| 依赖 | 本地文件 | 来源与许可 |
| --- | --- | --- |
| markdown-it 14.1.0 | [markdown-it.min.js](markdown-it.min.js) | [npm 发布包](https://registry.npmjs.org/markdown-it/-/markdown-it-14.1.0.tgz) 的 dist 文件；[MIT 许可](markdown-it.LICENSE) |
| Chart.js 4.5.1 | [chart.umd.min.js](chart.umd.min.js) | [npm 发布包](https://registry.npmjs.org/chart.js/-/chart.js-4.5.1.tgz)；[MIT 许可](chart.js.LICENSE) |
| Tabler Icons | [12 个 SVG 图标](tabler) | [官方项目](https://github.com/tabler/tabler-icons)；[MIT 许可](tabler/LICENSE) |

Markdown 解析器用于回答与证据渲染，[markdown.js](../markdown.js) 关闭原始 HTML 和图片，保留默认危险链接过滤。更新时运行 Markdown 安全及流式回显测试。

Chart.js 用于用量柱状图、折线图与交互提示。Tabler 图标用于导航、发送、导出和记录标识，通过 CSS mask 继承控件颜色，不请求外部图标服务。
