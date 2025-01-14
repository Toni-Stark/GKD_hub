package li.songe.gkd.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.blankj.utilcode.util.ScreenUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import li.songe.gkd.util.toast


@Serializable
data class GkdAction(
    val selector: String,
    val quickFind: Boolean = false,
    val fastQuery: Boolean = false,
    val action: String? = null,
    val position: RawSubscription.Position? = null,
)

@Serializable
data class ActionResult(
    val action: String?,
    val result: Boolean,
    val shizuku: Boolean = false,
)

val isUserNameInputComplete = MutableStateFlow(false)

sealed class ActionPerformer(val action: String) {
    abstract fun perform(
        context: AccessibilityService,
        node: AccessibilityNodeInfo,
        position: RawSubscription.Position?,
        shizukuClickFc: ((x: Float, y: Float) -> Boolean?)? = null,
    ): ActionResult

    data object ClickNode : ActionPerformer("clickNode") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            )
        }
    }

    data object ClickCenter : ActionPerformer("clickCenter") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?,
        ): ActionResult {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val p = position?.calc(rect)
            val x = p?.first ?: ((rect.right + rect.left) / 2f)
            val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
            return ActionResult(
                action = action,
                // TODO 在分屏/小窗模式下会点击到应用界面外部导致误触其它应用
                result = if (0 <= x && 0 <= y && x <= ScreenUtils.getScreenWidth() && y <= ScreenUtils.getScreenHeight()) {
                    val result = shizukuClickFc?.invoke(x, y)
                    if (result != null) {
                        return ActionResult(action, result, true)
                    }
                    val gestureDescription = GestureDescription.Builder()
                    val path = Path()
                    path.moveTo(x, y)
                    gestureDescription.addStroke(
                        GestureDescription.StrokeDescription(
                            path, 0, ViewConfiguration.getTapTimeout().toLong()
                        )
                    )
                    context.dispatchGesture(gestureDescription.build(), null, null)
                    true
                } else {
                    false
                }
            )
        }
    }


    data object Click : ActionPerformer("click") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            if (node.isClickable) {
                val result = ClickNode.perform(context, node, position)
                if (result.result) {
                    return result
                }
            }
            return ClickCenter.perform(context, node, position, shizukuClickFc)
        }
    }

    data object LongClickNode : ActionPerformer("longClickNode") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            )
        }
    }

    data object LongClickCenter : ActionPerformer("longClickCenter") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val p = position?.calc(rect)
            val x = p?.first ?: ((rect.right + rect.left) / 2f)
            val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
            // 内部的 DEFAULT_LONG_PRESS_TIMEOUT 常量是 400
            // 而 ViewConfiguration.getLongPressTimeout() 返回 300, 这将导致触发普通的 click 事件
            return ActionResult(
                action = action,
                result = if (0 <= x && 0 <= y && x <= ScreenUtils.getScreenWidth() && y <= ScreenUtils.getScreenHeight()) {
                    val gestureDescription = GestureDescription.Builder()
                    val path = Path()
                    path.moveTo(x, y)
                    gestureDescription.addStroke(
                        GestureDescription.StrokeDescription(
                            path, 0, 400L
                        )
                    )
                    // TODO 传入处理 callback
                    context.dispatchGesture(gestureDescription.build(), null, null)
                    true
                } else {
                    false
                }
            )
        }
    }

    data object LongClick : ActionPerformer("longClick") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            if (node.isLongClickable) {
                val result = LongClickNode.perform(context, node, position)
                if (result.result) {
                    return result
                }
            }
            return LongClickCenter.perform(context, node, position, shizukuClickFc)
        }
    }

    data object Back : ActionPerformer("back") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            return ActionResult(
                action = action,
                result = context.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            )
        }
    }


    data object InputUserName : ActionPerformer("inputUserName") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            toast("触发输入效果")

            val coroutineScope = CoroutineScope(Dispatchers.Default)
            val job = coroutineScope.launch {
                repeat(5) { index -> // 执行5次
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(1000) // 每次延迟1秒

                    node?.let {
                        toast("输入账号")
                        Log.d("输入账号", "节点子项数量: ${it.childCount}")
                        setText(it, "15502354225")
                    }
                }
            }

            // 等待协程完成
            runBlocking {
                job.join()
            }

            return ActionResult(
                action = action,
                result = true // true 表示操作成功
            )
        }
    }
    data object InputPassWord : ActionPerformer("inputPassWord") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            toast("触发输入效果")

            val coroutineScope = CoroutineScope(Dispatchers.Default)
            val job = coroutineScope.launch {
                repeat(5) { index -> // 执行5次
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(1000) // 每隔1秒执行一次

                    node?.let {
                        toast("输入密码")
                        Log.d("输入密码", "节点子项数量: ${it.childCount}")
                        setText(it, "15502354225@aA")
                    }
                }
            }

            // 等待协程执行完成
            runBlocking {
                job.join()
            }

            return ActionResult(
                action = action,
                result = true // 返回 true 表示执行成功
            )
        }
    }


    // 通用输入方法
    suspend fun performInput(
        node: AccessibilityNodeInfo,
        content: String,
        label: String
    ) {
        try {
            // 获取焦点
            if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                toast("$label 获取焦点成功")
            } else {
                toast("$label 获取焦点失败")
            }
            delay(500) // 模拟操作延迟

            // 输入内容
            setText(node, content)
            toast("输入内容：$content")
            delay(500)

            // 失去焦点
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)) {
                toast("$label 失去焦点成功")
            } else {
                toast("$label 失去焦点失败")
            }
        } catch (e: Exception) {
            toast("操作失败: ${e.message}")
        }
    }

    // 输入用户名
    data object InputUserNameMutex : ActionPerformer("inputUserNameMutex") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch {
                try {
                    toast("开始输入用户名")
                    performInput(node, "15502354225", "用户名框") // 输入用户名
                    isUserNameInputComplete.value = true // 标志设置为完成
                } finally {
                    delay(200) // 状态恢复原状
                    scope.cancel() // 避免协程泄漏
                }
            }
            return ActionResult(action = action, result = true)
        }
    }

    // 输入密码
    data object InputPassWordMutex : ActionPerformer("inputPassWordMutex") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch {
                try {
                    toast("等待用户名输入完成")
                    isUserNameInputComplete.collect { complete ->
                        if (complete) {
                            toast("开始输入密码")
                            performInput(node, "15502354225@aA", "密码框") // 输入密码
                            isUserNameInputComplete.value = false
                        }
                    }
                } finally {
                    scope.cancel() // 避免协程泄漏
                }
            }
            return ActionResult(action = action, result = true)
        }
    }

    // 输入验证码
    data object InputCodeMutex : ActionPerformer("inputCodeMutex") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch {
                try {
                    toast("等待验证码输入完成")
                    isUserNameInputComplete.collect { complete ->
                        if (complete) {
                            toast("开始输入验证码")
                            performInput(node, "122356", "验证码框") // 输入密码
                            isUserNameInputComplete.value = false
                        }
                    }
                } finally {
                    scope.cancel() // 避免协程泄漏
                }
            }
            return ActionResult(action = action, result = true)
        }
    }

    data object LoginBind : ActionPerformer("loginBind") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?,
        ): ActionResult {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val p = position?.calc(rect)
            val x = p?.first ?: ((rect.right + rect.left) / 2f)
            val y = p?.second ?: ((rect.bottom + rect.top) / 2f)
            if (x < 0 || y < 0 || x > ScreenUtils.getScreenWidth() || y > ScreenUtils.getScreenHeight()) {
                Log.w("LoginBind", "点击点超出屏幕范围: ($x, $y)")
                return ActionResult(action, false)
            }
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch {
                try {
                    // 延迟 6 秒
                    delay(6000)
                    // 执行点击逻辑
                    val result = shizukuClickFc?.invoke(x, y) ?: run {
                        val gestureDescription = GestureDescription.Builder()
                        val path = Path()
                        path.moveTo(x, y)
                        gestureDescription.addStroke(
                            GestureDescription.StrokeDescription(
                                path, 0, ViewConfiguration.getTapTimeout().toLong()
                            )
                        )
                        context.dispatchGesture(gestureDescription.build(), null, null)
                    }

                    Log.d("LoginBind", "延迟点击完成: ($x, $y), 结果: $result")
                } catch (e: Exception) {
                    Log.e("LoginBind", "延迟点击失败: ${e.message}")
                } finally {
                    scope.cancel() // 释放协程
                }
            }
            return ActionResult(action, true)
        }
    }

    data object InputCode : ActionPerformer("inputCode") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            toast("触发输入效果")

            val coroutineScope = CoroutineScope(Dispatchers.Default)
            // 启动协程
            coroutineScope.launch {
                repeat(1) { index ->// 执行5次
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    delay(1000) // 每隔1秒执行一次，可以根据需要调整时间间隔
                    if (node != null) {
                        // 设置文本内容
                        setText(node, "15502354225")
                    }
                }
                // 执行完成后的逻辑
            }

            // 阻塞主线程，以等待协程执行完成
            Thread.sleep(3000) // 等待6秒，确保协程执行完成
            return ActionResult(
                action = action,
                result = false
            )
        }
    }

    fun setText(nodeInfo: AccessibilityNodeInfo?, text: String) {
        if (nodeInfo == null) return
        // 模拟设置文本
        val arguments = Bundle()
        toast("触发输入效果1")
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    data object ClickPosition : ActionPerformer("clickPosition") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            toast("触发切换tab效果")
            val coroutineScope = CoroutineScope(Dispatchers.Default)
            // 启动协程
            coroutineScope.launch {
                repeat(5) { index ->// 执行5次
                    delay(3000) // 每隔1秒执行一次，可以根据需要调整时间间隔

                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if(node.childCount > index){
                        node.getChild(index)

                        val gestureDescription = GestureDescription.Builder()
                        val path = Path()
                        val width: Int = rect.width()
                        val height: Int = rect.top
                        val childWidth: Int = width / node.childCount
                        // 在这里执行你的操作
                        path.moveTo(childWidth*(index+1)*1F, (height+90)*1F)

                        gestureDescription.addStroke(
                            GestureDescription.StrokeDescription(
                                path, 0, 200L
                            )
                        )
                        context.dispatchGesture(gestureDescription.build(), null, null)
                    }
                }
                // 执行完成后的逻辑
            }

            // 阻塞主线程，以等待协程执行完成
            Thread.sleep(18000) // 等待6秒，确保协程执行完成

            return ActionResult(
                action = action,
                result = false
            )
        }
    }
    data object HandleUp : ActionPerformer("handleUp") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            toast("触发向上滚动效果")
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            )
        }
    }
    data object HandleDown : ActionPerformer("handleDown") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            toast("触发向下滚动效果")
            return ActionResult(
                action = action,
                result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            )
        }
    }

    fun performRightSwiper(context:AccessibilityService, startX: Int, endX: Int, durationMillis: Int) {
        val path = Path()
        path.moveTo(startX.toFloat(), 800f) // 从屏幕左侧某个位置开始，位于屏幕中央高度
        path.lineTo(endX.toFloat(), 700f) // 向右滑动到结束位置

        val builder = GestureDescription.Builder()
        builder.addStroke(StrokeDescription(path, 0, durationMillis.toLong()))
        val gesture = builder.build()

        context.dispatchGesture(gesture, null, null)
    }

    data object HandleStart : ActionPerformer("handleStart") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            toast("向右滚动")
            Log.d("向右滚动效果", node.childCount.toString())

            val path = Path()
            path.moveTo(950F, 1400F) // 起始点在屏幕底部中央
            path.lineTo(120F, 1333F) // 结束点在屏幕顶部中央
            val strokeDesc = StrokeDescription(
                path, 0, 500
            ) // 持续时间1000毫秒

            val gestureDesc = GestureDescription.Builder()
                .addStroke(strokeDesc)
                .build()

            context.dispatchGesture(gestureDesc, null, null)
            return ActionResult(
                action = action,
                result = true
            )
        }
    }
    private fun allChildrenTall(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val childRect = Rect()
            child.getBoundsInScreen(childRect)
            Log.d("Log3", childRect.height().toString())
            if (childRect.height() <= 50) {
                return false // 如果有一个子节点的高度不满足条件，则返回false
            }
            // 注意：这里我们没有递归检查子节点的子节点，因为题目只要求当前节点的子节点高度
        }
        return true // 所有子节点的高度都大于50
    }

    private fun mastClickCenter(context:AccessibilityService, rect:Rect) {
        val left = rect.left+((rect.right-rect.left)/2)
        val top = rect.top+((rect.bottom-rect.top)/2)
        Log.d("Log55", left.toString())
        Log.d("Log55", top.toString())
        val path = Path()
        path.moveTo(left.toFloat(), top.toFloat()) // x, y为点击坐标
        val builder = GestureDescription.Builder()
        builder.addStroke(StrokeDescription(path, 0, 1))
        context.dispatchGesture(builder.build(), null, null)
    }

    private fun mastUpSwiper(context:AccessibilityService) {
        val path = Path()
        path.moveTo(724F, 1840F) // 起始点在屏幕底部中央
        path.lineTo(622F, 733F) // 结束点在屏幕顶部中央
        toast("尝试滚动页面")
        val strokeDesc = StrokeDescription(
            path, 0, 150
        ) // 持续时间1000毫秒

        val gestureDesc = GestureDescription.Builder()
            .addStroke(strokeDesc)
            .build()

        context.dispatchGesture(gestureDesc, null, null)
    }

    private fun mastDownSwiper(context:AccessibilityService, node:AccessibilityNodeInfo) {
        val packageName = context.rootInActiveWindow?.packageName?.toString() ?: "";
        Log.d("Log877", packageName)
        if (packageName != "com.miui.home") {
            val path = Path()
            path.moveTo(622F, 733F) // 起始点在屏幕顶部中央
            path.lineTo(724F, 1840F) // 结束点在屏幕底部中央
            toast("尝试滚动页面")
            val strokeDesc = StrokeDescription(
                path, 0, 150
            ) // 持续时间1000毫秒

            val gestureDesc = GestureDescription.Builder()
                .addStroke(strokeDesc)
                .build()

            context.dispatchGesture(gestureDesc, null, null)
        }
    }

    private fun mastStartSwiper(context:AccessibilityService) {
        val path = Path()
        path.moveTo(1050F, 1050F) // 起始点在屏幕底部中央
        path.lineTo(722F, 1050F) // 结束点在屏幕顶部中央
        toast("尝试滚动页面")
        val strokeDesc = StrokeDescription(
            path, 0, 150
        ) // 持续时间1000毫秒

        val gestureDesc = GestureDescription.Builder()
            .addStroke(strokeDesc)
            .build()

        context.dispatchGesture(gestureDesc, null, null)
    }

    fun processAction (context:AccessibilityService, node: AccessibilityNodeInfo, num:Int){
        mastUpSwiper(context);
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        mastUpSwiper(context);
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        mastUpSwiper(context);
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        mastUpSwiper(context);

        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        mastDownSwiper(context,node);
//        Thread.sleep(1000)
//        mastStartSwiper(context)
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        val item = node.getChild(num);
        val childRect = Rect()
        item.getBoundsInScreen(childRect)
        mastClickCenter(context, childRect)
        if(num<node.childCount-1){
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            processAction(context, node, num+1);
        } else {
            toast("执行完毕")
            mastStartSwiper(context)
            mastStartSwiper(context)
        }
    }
    fun findSpecialNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        // 如果当前节点为空，则返回null
        if (node == null) return null

        // 检查当前节点是否满足条件
        if (node.childCount > 1 && allChildrenTall(node)) {
            return node // 如果当前节点满足条件，则返回它
        }
        // 如果当前节点不满足条件，则递归检查其子节点
        Log.d("Log4", node.childCount.toString())
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findSpecialNode(child) // 递归调用
            if (result != null) {
                return result // 如果在子节点中找到了满足条件的节点，则返回它
            }
        }

        // 如果没有找到满足条件的节点，则返回null
        return null
    }

    data object OnePathClickTab : ActionPerformer("onePathClickTab") {
        override fun perform(
            context: AccessibilityService,
            node: AccessibilityNodeInfo,
            position: RawSubscription.Position?,
            shizukuClickFc: ((x: Float, y: Float) -> Boolean?)?
        ): ActionResult {
            toast("触发tab验证效果")
            Log.d("Log99", node.childCount.toString())
            val specialNode: AccessibilityNodeInfo? = findSpecialNode(node) // 假设rootNode是已经获取到的AccessibilityNodeInfo
            if (specialNode != null) {
                // 处理找到的节点
                Log.d("Log1", specialNode.childCount.toString())
                processAction(context, specialNode, 0)
                Thread.sleep((specialNode.childCount*7000).toLong()) // 等待6秒，确保协程执行完成
            } else {
                Log.d("Log2", "No special node found")
            }

            // 用于存储宽高相同的符合条件的元素
            return ActionResult(
                action = action,
                result = true
            )
        }

    }

    companion object {
        private val allSubObjects by lazy {
            arrayOf(ClickNode, ClickCenter, Click, LongClickNode, LoginBind, LongClickCenter, LongClick,HandleStart,InputUserName,InputPassWord,InputUserNameMutex,InputCodeMutex,InputPassWordMutex,InputCode, ClickPosition,HandleUp, HandleDown, Back, OnePathClickTab)
        }

        fun getAction(action: String?): ActionPerformer {
            return allSubObjects.find { it.action == action } ?: Click
        }
    }
}

