package com.hanwenjiang.uhfrfid

import com.hanwenjiang.uhfrfid.ui.MainActivity as ComposeMainActivity

/**
 * 清单里声明的是根包下的 `.MainActivity`（`AndroidManifest.xml` 不动），
 * 而界面代码都在 [com.hanwenjiang.uhfrfid.ui] 里 —— 这个空壳就是为了对上名字。
 *
 * <p>不需要 override 任何东西：全部逻辑在父类里。以后想加"启动就带某个页面"
 * 之类的 intent 处理，也在这儿加。
 */
class MainActivity : ComposeMainActivity()
