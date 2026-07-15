package com.mamba.picme.server.admin

import com.mamba.picme.server.analytics.formatCostCny
import com.mamba.picme.server.llm.ChannelRow
import com.mamba.picme.server.llm.renderModelMapLines
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.ButtonType
import kotlinx.html.br
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.meta
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import kotlinx.html.stream.createHTML

/**
 * 服务端渲染 HTML 页面（kotlinx.html）。无前端构建、无 CDN，内联 SVG 图表。
 * 每个 page 函数返回完整 HTML 字符串，由 AdminRoutes 用 respondText 输出。
 */
object AdminViews {

    fun loginPage(failed: Boolean = false): String = createHTML().html {
        adminHead("登录 · PoLang 管理后台")
        body {
            div("wrap") {
                h1 { +"PoLang 管理后台" }
                if (failed) p("err") { +"密码错误" }
                form(action = "/admin/login", method = FormMethod.post) {
                    p { textInput(name = "password", classes = "pw") { placeholder = "ADMIN_TOKEN" } }
                    p { input(type = InputType.submit, classes = "btn") { value = "登录" } }
                }
            }
        }
    }

    fun overviewPage(ov: OverviewRow, series: List<DayBucket>): String = createHTML().html {
        adminHead("概览 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"概览（今日，UTC 自然日）" }
            div("cards") {
                statCard("总用户数", ov.totalUsers.toString())
                statCard("今日新增", ov.newUsersToday.toString())
                statCard("今日调用", ov.callsToday.toString())
                statCard("今日 Token", ov.tokensToday.toString())
                statCard("今日成本 ¥", fmt(ov.costToday))
                statCard("今日出口字节", ov.bytesToday.toString())
                statCard("今日 blocked", ov.blockedToday.toString())
            }
            h2 { +"近 ${series.size} 天 调用数" }
            unsafe { raw(svgBars(series.map { it.calls.toDouble() }, series.map { it.day })) }
            h2 { +"近 ${series.size} 天 成本 ¥" }
            unsafe { raw(svgBars(series.map { it.cost }, series.map { it.day })) }
        }
    }

    fun usersPage(rows: List<UserRow>): String = createHTML().html {
        adminHead("用户 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"用户（${rows.size}）" }
            table {
                tr {
                    th { +"ID" }
                    th { +"邮箱" }
                    th { +"API Token" }
                    th { +"状态" }
                    th { +"注册时间" }
                    th { +"调用" }
                    th { +"Token 用量" }
                    th { +"成本 ¥" }
                    th { +"最后活跃" }
                }
                rows.forEach { u ->
                    tr {
                        td { +u.id.toString() }
                        td { a("/admin/users/${u.id}") { +u.email } }
                        td {
                            if (u.hasToken) {
                                span("tok") { +u.apiTokenMasked }
                                +" "
                                button(type = ButtonType.button, classes = "btn-sm tok-copy") {
                                    attributes["onclick"] = "tokCopy(${u.id}, this)"
                                    +"复制"
                                }
                            } else {
                                +"—"
                            }
                        }
                        td { +u.status }
                        td { +fmtTs(u.createdAt) }
                        td { +u.calls.toString() }
                        td { +u.totalTokens.toString() }
                        td { +fmt(u.cost) }
                        td { +(u.lastActive?.let { fmtTs(it) } ?: "—") }
                    }
                }
            }
            script {
                unsafe {
                    raw(
                        """function tokCopy(id,btn){fetch('/admin/users/'+id+'/token',{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(d){return navigator.clipboard.writeText(d.token)}).then(function(){var o=btn.textContent;btn.textContent='✓';setTimeout(function(){btn.textContent=o},1200)}).catch(function(){btn.textContent='失败';setTimeout(function(){btn.textContent='复制'},1200)})}""",
                    )
                }
            }
        }
    }

    fun userDetailPage(d: UserDetail, calls: List<CallRow>): String = createHTML().html {
        adminHead("用户详情 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"${d.email}（#${d.id}）" }
            div("cards") {
                statCard("状态", d.status)
                statCard("注册时间", fmtTs(d.createdAt))
                statCard("成功调用", d.calls.toString())
                statCard("Token", d.totalTokens.toString())
                statCard("成本 ¥", fmt(d.cost))
                statCard("blocked", d.blocked.toString())
                statCard("出口字节", d.bytes.toString())
            }
            h2 { +"最近调用（${calls.size}）" }
            table {
                tr {
                    th { +"时间" }
                    th { +"模型" }
                    th { +"Provider" }
                    th { +"Prompt" }
                    th { +"Completion" }
                    th { +"Total" }
                    th { +"成本 ¥" }
                    th { +"字节" }
                    th { +"状态" }
                    th { +"ms" }
                }
                calls.forEach { c ->
                    tr {
                        td { +fmtTs(c.createdAt) }
                        td { +c.model }
                        td { +c.provider }
                        td { +(c.promptTokens?.toString() ?: "—") }
                        td { +(c.completionTokens?.toString() ?: "—") }
                        td { +(c.totalTokens?.toString() ?: "—") }
                        td { +fmt(c.costCny) }
                        td { +c.respBytes.toString() }
                        td { +c.status }
                        td { +(c.latencyMs?.toString() ?: "—") }
                    }
                }
            }
        }
    }

    fun trafficPage(series: List<DayBucket>): String = createHTML().html {
        adminHead("流量 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"流量（近 ${series.size} 天，UTC）" }
            h2 { +"每日 Token 总量" }
            unsafe { raw(svgBars(series.map { it.totalTokens.toDouble() }, series.map { it.day })) }
            h2 { +"每日明细" }
            table {
                tr {
                    th { +"日期" }
                    th { +"调用" }
                    th { +"blocked" }
                    th { +"Prompt" }
                    th { +"Completion" }
                    th { +"Total Token" }
                    th { +"成本 ¥" }
                    th { +"出口字节" }
                }
                series.reversed().forEach { b ->
                    tr {
                        td { +b.day }
                        td { +b.calls.toString() }
                        td { +b.blocked.toString() }
                        td { +b.promptTokens.toString() }
                        td { +b.completionTokens.toString() }
                        td { +b.totalTokens.toString() }
                        td { +fmt(b.cost) }
                        td { +b.bytes.toString() }
                    }
                }
            }
        }
    }

    fun channelsPage(channels: List<ChannelRow>, error: String? = null): String = createHTML().html {
        adminHead("渠道 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"渠道" }
            if (error != null) p("err") { +error }
            p {
                a("/admin/channels/new", classes = "btn") { +"新增渠道" }
            }
            table {
                tr {
                    th { +"名称" }
                    th { +"Token" }
                    th { +"默认模型" }
                    th(classes = "col-toggle") { +"启用" }
                    th(classes = "col-active") { +"当前生效" }
                    th(classes = "col-actions") { +"操作" }
                }
                channels.forEach { ch ->
                    tr {
                        td { +ch.name }
                        td {
                            span("tok") { +ch.apiTokenMasked }
                            if (ch.hasToken) {
                                +" "
                                button(type = ButtonType.button, classes = "btn-sm tok-copy") {
                                    attributes["onclick"] = "tokCopy(${ch.id}, this)"
                                    +"复制"
                                }
                            }
                        }
                        td { +(ch.defaultModel.ifBlank { "严格" }) }
                        td {
                            form(action = "/admin/channels/${ch.id}/toggle", method = FormMethod.post, classes = "inline") {
                                input(type = InputType.submit, classes = "btn-sm ${if (ch.enabled) "" else "btn-go"}") {
                                    value = if (ch.enabled) "停用" else "启用"
                                }
                            }
                        }
                        td {
                            when {
                                ch.isActive -> span("active-badge") { +"● 生效中" }
                                ch.enabled -> form(action = "/admin/channels/${ch.id}/activate", method = FormMethod.post, classes = "inline") {
                                    input(type = InputType.submit, classes = "btn-sm btn-go") { value = "设为生效" }
                                }
                                else -> +"—"
                            }
                        }
                        td {
                            div("row-actions") {
                                a("/admin/channels/${ch.id}/edit", classes = "btn-sm btn-primary") { +"编辑" }
                                if (!ch.isActive) {
                                    form(action = "/admin/channels/${ch.id}/delete", method = FormMethod.post, classes = "inline") {
                                        attributes["onsubmit"] = "return confirm('确定删除该渠道？操作不可恢复。')"
                                        input(type = InputType.submit, classes = "btn-sm btn-danger") { value = "删除" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            script {
                unsafe {
                    raw(
                        """function tokCopy(id,btn){fetch('/admin/channels/'+id+'/token',{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(d){return navigator.clipboard.writeText(d.token)}).then(function(){var o=btn.textContent;btn.textContent='✓';setTimeout(function(){btn.textContent=o},1200)}).catch(function(){btn.textContent='失败';setTimeout(function(){btn.textContent='复制'},1200)})}""",
                    )
                }
            }
        }
    }

    fun channelFormPage(existing: ChannelRow? = null): String = createHTML().html {
        val title = if (existing == null) "新增渠道" else "编辑渠道"
        val action = if (existing == null) "/admin/channels" else "/admin/channels/${existing.id}"
        adminHead("$title · PoLang 管理后台")
        body {
            navBar()
            h1 { +title }
            form(action = action, method = FormMethod.post, classes = "chan-form") {
                p {
                    label { +"名称（≤32）" }
                    br()
                    textInput(name = "name") {
                        value = existing?.name ?: ""
                        placeholder = "如 DeepSeek 直连"
                    }
                }
                p {
                    label { +"类型" }
                    br()
                    select {
                        name = "kind"
                        option {
                            value = "gateway"
                            if (existing?.kind == "gateway") selected = true
                            +"gateway"
                        }
                        option {
                            value = "direct"
                            if (existing == null || existing.kind == "direct") selected = true
                            +"direct"
                        }
                    }
                }
                p {
                    label { +"BaseURL" }
                    br()
                    textInput(name = "base_url") {
                        value = existing?.baseUrl ?: ""
                        placeholder = "https://..."
                    }
                }
                p {
                    label { +"鉴权方式" }
                    br()
                    select {
                        name = "auth_style"
                        option {
                            value = "bearer"
                            if (existing?.authStyle != "cf_aig") selected = true
                            +"bearer (Authorization: Bearer)"
                        }
                        option {
                            value = "cf_aig"
                            if (existing?.authStyle == "cf_aig") selected = true
                            +"cf_aig (cf-aig-authorization)"
                        }
                    }
                }
                p {
                    label { +"API Token（编辑时留空=保持不变）" }
                    br()
                    input(type = InputType.password, name = "api_token") {
                        placeholder = if (existing != null) "••••（留空不变）" else ""
                    }
                }
                p {
                    label { +"模型映射（每行 请求名=上游名）" }
                    br()
                    textArea {
                        name = "model_map"
                        rows = "6"
                        cols = "50"
                        +(existing?.modelMap?.let { renderModelMapLines(it) } ?: "deepseek-chat=glm-5.2")
                    }
                }
                p {
                    label { +"默认模型（留空=严格校验，请求不支持的模型时返回 400）" }
                    br()
                    textInput(name = "default_model") {
                        value = existing?.defaultModel ?: ""
                        placeholder = "如 deepseek-v4-flash"
                    }
                }
                div("form-actions") {
                    label("cb") {
                        input(type = InputType.checkBox, name = "enabled") {
                            value = "1"
                            if (existing?.enabled ?: true) checked = true
                        }
                        +" 启用此渠道"
                    }
                    span("form-actions-right") {
                        a("/admin/channels", classes = "btn-ghost") { +"取消" }
                        input(type = InputType.submit, classes = "btn") { value = "保存" }
                    }
                }
            }
        }
    }

    fun apkPage(
        fileExists: Boolean,
        fileSize: String,
        lastModified: String,
        version: String,
        cosUrl: String,
        cosConfigured: Boolean,
        message: String? = null,
    ): String = createHTML().html {
        adminHead("APK 管理 · PoLang 管理后台")
        body {
            navBar()
            h1 { +"APK 下载包管理" }
            if (message != null) {
                p {
                    style = if (message.startsWith("成功")) "color:#16a34a;font-size:14px;margin:8px auto;max-width:640px;padding:0 20px" else "color:#dc2626;font-size:14px;margin:8px auto;max-width:640px;padding:0 20px"
                    +message
                }
            }
            if (!cosConfigured) {
                p {
                    style = "color:#dc2626;font-size:14px;margin:12px auto;max-width:640px;padding:0 20px"
                    +"⚠ COS 未配置（COS_SECRET_ID / COS_SECRET_KEY / COS_BUCKET 为空），请在 /etc/picme/server.env 中填写后重启服务"
                }
            }
            if (fileExists) {
                h2 { +"当前 APK（COS）" }
                table {
                    tr { th { +"版本" }; td { +version.ifBlank { "—" } } }
                    tr { th { +"大小" }; td { +fileSize } }
                    tr { th { +"上传时间" }; td { +lastModified } }
                    tr {
                        th { +"操作" }
                        td {
                            a(href = "https://api.polang.net/download", target = "_blank") { +"下载页" }
                            +" · "
                            a(href = cosUrl, target = "_blank") { +"COS 直链" }
                        }
                    }
                }
            } else {
                p {
                    style = "color:#6b7280;font-size:14px;margin:12px auto;max-width:640px;padding:0 20px"
                    +"COS 上暂无 APK 文件"
                }
            }
            h2 { +"上传新版本" }
            div("chan-form") {
                p {
                    label { +"版本号（如 1.0.11）" }
                    input(type = InputType.text, name = "version") {
                        attributes["id"] = "apk-version"
                        placeholder = "1.0.11"
                    }
                }
                p {
                    label { +"选择 APK 文件（.apk）" }
                    br()
                    input(type = InputType.file, name = "apkfile") {
                        attributes["id"] = "apk-file"
                        accept = ".apk"
                    }
                }
                // 进度条容器
                div {
                    attributes["id"] = "progress-container"
                    style = "display:none;margin:12px 0"
                    div {
                        style = "background:#e5e7eb;border-radius:6px;height:20px;overflow:hidden"
                        div {
                            attributes["id"] = "progress-bar"
                            style = "background:#2563eb;height:100%;width:0%;transition:width .2s ease;text-align:center;color:#fff;font-size:12px;line-height:20px"
                            +"0%"
                        }
                    }
                    p {
                        attributes["id"] = "progress-text"
                        style = "font-size:12px;color:#6b7280;margin:4px 0 0"
                        +"准备上传..."
                    }
                }
                p {
                    button(type = ButtonType.button, classes = "btn btn-primary") {
                        attributes["id"] = "upload-btn"
                        attributes["onclick"] = "uploadApk()"
                        +"上传到 COS"
                    }
                }
            }
            script {
                unsafe {
                    raw(
                        """
                        function uploadApk(){
                          var fileInput=document.getElementById('apk-file');
                          var versionInput=document.getElementById('apk-version');
                          var btn=document.getElementById('upload-btn');
                          var bar=document.getElementById('progress-bar');
                          var container=document.getElementById('progress-container');
                          var text=document.getElementById('progress-text');
                          var file=fileInput.files[0];
                          if(!file){alert('请选择 APK 文件');return;}
                          if(!file.name.endsWith('.apk')){alert('请上传 .apk 文件');return;}
                          var form=new FormData();
                          form.append('version',versionInput.value.trim()||'');
                          form.append('apkfile',file);
                          var xhr=new XMLHttpRequest();
                          xhr.open('POST','/admin/apk/upload',true);
                          xhr.upload.onprogress=function(e){
                            if(e.lengthComputable){
                              var pct=Math.round(e.loaded/e.total*100);
                              bar.style.width=pct+'%';
                              bar.textContent=pct+'%';
                              text.textContent='已上传 '+formatBytes(e.loaded)+' / '+formatBytes(e.total);
                            }
                          };
                          xhr.onload=function(){
                            if(xhr.status===200||xhr.status===302){
                              window.location.reload();
                            }else{
                              text.textContent='上传失败：'+xhr.statusText;
                              text.style.color='#dc2626';
                              btn.disabled=false;
                              btn.textContent='上传到 COS';
                            }
                          };
                          xhr.onerror=function(){
                            text.textContent='网络错误，请重试';
                            text.style.color='#dc2626';
                            btn.disabled=false;
                            btn.textContent='上传到 COS';
                          };
                          container.style.display='block';
                          btn.disabled=true;
                          btn.textContent='上传中...';
                          xhr.send(form);
                        }
                        function formatBytes(b){
                          if(b===0)return'0 B';
                          var k=1024,s=['B','KB','MB','GB'];
                          var i=Math.floor(Math.log(b)/Math.log(k));
                          return parseFloat((b/Math.pow(k,i)).toFixed(2))+' '+s[i];
                        }
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    // ── 公共片段 ──

    private fun HTML.adminHead(title: String) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +title }
            style {
                unsafe {
                    raw(
                        """
                        *{box-sizing:border-box}
                        body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;margin:0;background:#f0f2f5;color:#333;padding-bottom:40px}
                        body>h1,body>h2,body>.cards,body>table,body>p{max-width:1200px;margin-left:auto;margin-right:auto;padding-left:24px;padding-right:24px}
                        body>h1{font-size:24px;font-weight:600;margin-top:24px;margin-bottom:16px;color:#1f2d3d}
                        body>h2{font-size:16px;font-weight:600;color:#333;margin-top:24px;margin-bottom:12px}
                        .wrap{max-width:420px;margin:80px auto;padding:32px;background:#fff;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,.08)}
                        .cards{display:flex;flex-wrap:wrap;gap:16px;padding:16px 24px}
                        .card{background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:16px 20px;min-width:140px;box-shadow:0 2px 8px rgba(0,0,0,.06);transition:transform .2s}
                        .card:hover{transform:translateY(-2px)}
                        .card-label{font-size:13px;color:#666;margin-bottom:4px}
                        .card-value{font-size:24px;font-weight:600;color:#006eff}
                        .nav{background:#1f2d3d;color:#fff;padding:0 24px;display:flex;align-items:center;gap:4px;height:56px;box-shadow:0 2px 8px rgba(0,0,0,.12);position:sticky;top:0;z-index:100}
                        .nav-brand{font-weight:600;font-size:16px;margin-right:32px;white-space:nowrap;color:#fff}
                        .nav-links{display:flex;gap:4px}
                        .nav-link{color:#b0b8c4;text-decoration:none;padding:8px 16px;border-radius:4px;font-size:14px;transition:all .2s}
                        .nav-link:hover{background:rgba(255,255,255,.1);color:#fff}
                        .nav-link.active{background:#006eff;color:#fff}
                        .nav-spacer{flex:1}
                        .nav-logout{color:#ff9c00}
                        .nav-logout:hover{background:rgba(255,156,0,.1);color:#ff9c00}
                        table{border-collapse:collapse;width:100%;background:#fff;font-size:13px;border:1px solid #e5e5e5;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.06)}
                        th,td{border-bottom:1px solid #f0f0f0;padding:12px 16px;text-align:left}
                        th{background:#fafafa;font-weight:600;color:#666;font-size:12px;text-transform:uppercase;letter-spacing:.5px}
                        tr:hover{background:#f5f7fa}
                        tr:last-child td{border-bottom:none}
                        td a{color:#006eff;text-decoration:none;font-weight:500}
                        td a:hover{text-decoration:underline}
                        .err{color:#e54545;background:#fff2f0;padding:8px 12px;border-radius:4px;border:1px solid #ffccc7}
                        .pw{padding:10px 12px;width:100%;border:1px solid #d9d9d9;border-radius:4px;font-size:14px;transition:border-color .2s}
                        .pw:focus{outline:none;border-color:#006eff}
                        .btn{padding:10px 20px;background:#006eff;color:#fff;border:none;border-radius:4px;font-size:14px;cursor:pointer;transition:all .2s;font-weight:500}
                        .btn:hover{background:#005ce6;transform:translateY(-1px);box-shadow:0 4px 12px rgba(0,110,255,.3)}
                        .chart{display:block;width:100%;max-width:800px;height:auto;margin:16px auto;background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:16px;box-shadow:0 2px 8px rgba(0,0,0,.06)}
                        .chan-form{max-width:640px;margin:24px auto;padding:0 24px}
                        .chan-form p{margin:12px 0}
                        .chan-form label{display:block;font-size:13px;color:#666;margin-bottom:6px;font-weight:500}
                        .chan-form input[type=text],.chan-form input[type=password],.chan-form select,.chan-form textarea{padding:10px 12px;border:1px solid #d9d9d9;border-radius:4px;width:100%;font-size:14px;font-family:inherit;transition:border-color .2s}
                        .chan-form input[type=text]:focus,.chan-form select:focus,.chan-form textarea:focus{outline:none;border-color:#006eff}
                        .chan-form textarea{font-family:monospace}
                        .inline{display:inline}
                        .btn-sm{padding:4px 12px;background:#f5f5f5;color:#666;border:1px solid #d9d9d9;border-radius:4px;font-size:12px;cursor:pointer;min-width:40px;text-align:center;height:28px;line-height:20px;display:inline-block;vertical-align:middle;transition:all .2s}
                        .btn-sm:hover{background:#e8e8e8}
                        .btn-primary{background:#006eff;color:#fff;border-color:#006eff}
                        .btn-primary:hover{background:#005ce6}
                        .btn-go{background:#0abf5b;color:#fff;border-color:#0abf5b}
                        .btn-go:hover{background:#09a94f}
                        .btn-danger{background:#e54545;color:#fff;border-color:#e54545}
                        .btn-danger:hover{background:#d13f3f}
                        .active-badge{color:#0abf5b;font-weight:600}
                        .tok{font-family:monospace;font-size:12px;color:#666;background:#f5f5f5;padding:2px 6px;border-radius:4px}
                        form{margin:0}
                        .row-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
                        .col-toggle{width:80px}
                        .col-active{width:100px}
                        .col-actions{width:120px}
                        .chan-form input[type=checkbox]{width:auto;margin:0}
                        .form-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:20px;padding-top:16px;border-top:1px solid #f0f0f0}
                        .form-actions-right{display:inline-flex;gap:8px;align-items:center}
                        .cb{display:inline-flex;align-items:center;gap:6px;font-size:14px;color:#333;cursor:pointer}
                        .btn-ghost{padding:10px 16px;color:#666;text-decoration:none;font-size:14px;border-radius:4px;border:1px solid #d9d9d9;background:#fff;transition:all .2s}
                        .btn-ghost:hover{color:#333;background:#f5f5f5;border-color:#b0b8c4}
                        @media (max-width:640px){
                        body>h1{font-size:20px}
                        body>h2{font-size:14px}
                        .nav{padding:0 16px;height:50px}
                        .nav-brand{font-size:14px;margin-right:16px}
                        .nav-link{padding:6px 10px;font-size:13px;white-space:nowrap}
                        .cards{padding:12px;gap:12px}
                        .card{min-width:0;flex:1 1 calc(50% - 8px);padding:12px 14px}
                        .card-value{font-size:20px}
                        table{display:block;overflow-x:auto;-webkit-overflow-scrolling:touch;white-space:nowrap}
                        th,td{padding:10px 12px}
                        .wrap{margin:40px auto;padding:20px}
                        }
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun FlowContent.navBar() {
        div("nav") {
            div("nav-brand") { +"PoLang 管理后台" }
            div("nav-links") {
                a("/admin", classes = "nav-link") { +"概览" }
                a("/admin/users", classes = "nav-link") { +"用户" }
                a("/admin/traffic", classes = "nav-link") { +"流量" }
                a("/admin/channels", classes = "nav-link") { +"渠道" }
                a("/admin/apk", classes = "nav-link") { +"APK" }
            }
            div("nav-spacer") {}
            a("/admin/logout", classes = "nav-link nav-logout") { +"退出" }
        }
        script {
            unsafe {
                raw(
                    """document.addEventListener('DOMContentLoaded',function(){var path=window.location.pathname;var links=document.querySelectorAll('.nav-link');links.forEach(function(link){if(link.getAttribute('href')===path){link.classList.add('active');}});});""",
                )
            }
        }
    }

    private fun FlowContent.statCard(label: String, value: String) {
        div("card") {
            div("card-label") { +label }
            div("card-value") { +value }
        }
    }

    /** 简易 SVG 柱状图：日期标签旋转 -40° 防重叠，密集时稀疏标注；每柱 <title> 悬浮提示。 */
    private fun svgBars(values: List<Double>, labels: List<String>): String {
        if (values.isEmpty()) return "<p>无数据</p>"
        val maxV = values.max().coerceAtLeast(1.0)
        val w = 760
        val plotH = 110
        val padBottom = 46
        val h = plotH + padBottom
        val barW = (w / values.size).coerceAtLeast(2)
        val step = if (values.size > 16) (values.size / 8).coerceAtLeast(1) else 1
        val sb = StringBuilder()
        sb.append("""<svg class="chart" viewBox="0 0 $w $h" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">""")
        values.forEachIndexed { i, v ->
            val barH = (v / maxV * plotH).toInt().coerceAtLeast(1)
            val x = i * barW
            val y = plotH - barH
            val cx = x + barW / 2
            sb.append("""<rect x="$x" y="$y" width="${(barW - 2).coerceAtLeast(1)}" height="$barH" rx="2" fill="#3b82f6"><title>${esc(labels[i])}: ${fmtVal(v)}</title></rect>""")
            if (i % step == 0) {
                sb.append("""<text x="$cx" y="${plotH + 8}" font-size="9" fill="#6b7280" text-anchor="end" transform="rotate(-40 $cx ${plotH + 8})">${esc(labels[i].takeLast(5))}</text>""")
            }
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun fmtVal(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else formatCostCny(v)

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun fmt(d: Double): String = formatCostCny(d)

    private fun fmtTs(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toString().take(19).replace("T", " ")
}
