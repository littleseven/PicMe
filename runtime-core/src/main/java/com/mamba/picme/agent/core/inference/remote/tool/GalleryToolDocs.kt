package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 跨 ToolService 共享的 @Tool 描述文案（LLM 可见）。
 *
 * `ChatToolService`（chat ReAct）与 `PoLangToolService`（飞书 RPA）服务不同 agent，
 * 多数同名工具的描述**按 agent 故意差异化**（如 `run_gallery_script`：chat 版含
 * `capability.dispatch` 写操作示例，飞书版为只读子集）——这不是漂移，是设计。
 *
 * 仅当两个 ToolService 的某条描述**逐字节相同**时，才提取到此处作为单一来源，
 * 避免真重复随维护走样。新增共享前先确认两边语义一致。
 *
 * 详见 `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` §2.4 路由策略。
 */
object GalleryToolDocs {

    /** `draw_chart` 工具描述：两 ToolService 完全一致，提取为单一来源。 */
    const val DRAW_CHART =
        "画出图表并渲染成真实图片展示给用户——这是展示图表的唯一方式，严禁用文字、" +
            "Markdown 表格、ASCII/emoji 画图（文字画的图用户看不到效果）。" +
            "先用 run_gallery_script 拿到数据，再把数据传给本工具画图。"
}
