package moe.ono.hooks.dispatcher

import de.robv.android.xposed.XC_MethodHook
import moe.ono.hooks._base.ApiHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks.item.chat.FakeFileRecall
import moe.ono.hooks.item.chat.MessageEncryptor
import moe.ono.hooks.item.chat.StickerPanelEntry
import moe.ono.hooks.item.developer.PatPatMenuEntry
import moe.ono.hooks.item.developer.QQMessageFetcher
import moe.ono.hooks.item.entertainment.ModifyTextMessage
import moe.ono.hooks.item.entertainment.RespondFace
import moe.ono.hooks.item.sigma.QQMessageTracker
import moe.ono.util.Logger
import java.lang.reflect.Modifier

@HookItem(path = "API/对应类型消息菜单构建时回调接口")
class MenuBuilderHook : ApiHookItem() {

    private val decorators: Array<OnMenuBuilder> = arrayOf(
        StickerPanelEntry(),
        QQMessageTracker(),
        QQMessageFetcher(),
        PatPatMenuEntry(), // ✅ 建议放中间，方便排查
        ModifyTextMessage(),
        RespondFace(),
        MessageEncryptor(),
        FakeFileRecall(),
    )

    override fun entry(classLoader: ClassLoader) {
        val baseClass = classLoader.loadClass(
            "com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent"
        )

        // 找到 “拿消息对象”的方法
        val aioMsgItemClz = classLoader.loadClass("com.tencent.mobileqq.aio.msg.AIOMsgItem")
        val getMsgMethodName = baseClass.declaredMethods.firstOrNull {
            it.returnType == aioMsgItemClz && it.parameterTypes.isEmpty()
        }?.name ?: run {
            Logger.e("MenuBuilderHook: cannot find getMsg method")
            return
        }

        // 找到 “构建菜单列表”的方法（更稳：返回类型 assignable to java.util.List）
        val getListMethodName = baseClass.declaredMethods.firstOrNull {
            Modifier.isAbstract(it.modifiers) &&
                java.util.List::class.java.isAssignableFrom(it.returnType) &&
                it.parameterTypes.isEmpty()
        }?.name ?: run {
            Logger.e("MenuBuilderHook: cannot find getMenuList method")
            return
        }

        val targets = decorators.flatMap { it.targetTypes.asIterable() }.toMutableSet()
        Logger.d("MenuBuilderHook: targets size=${targets.size} getList=$getListMethodName getMsg=$getMsgMethodName")

        for (target in targets) {
            try {
                val clz = classLoader.loadClass(target)
                val m = clz.getDeclaredMethod(getListMethodName)

                hookAfter(m) { param ->
                    val getMsgMethod = baseClass.getDeclaredMethod(getMsgMethodName).apply { isAccessible = true }
                    val aioMsgItem = runCatching { getMsgMethod.invoke(param.thisObject) }.getOrNull() ?: return@hookAfter

                    Logger.d("MenuBuilderHook target=$target msg=$aioMsgItem result=${param.result?.javaClass?.name}")

                    for (decorator in decorators) {
                        if (target in decorator.targetTypes) {
                            runCatching { decorator.onGetMenu(aioMsgItem, target, param) }
                                .onFailure { Logger.e("decorator.onGetMenu error: ${decorator.javaClass.name}", it) }
                        }
                    }
                }
            } catch (_: NoSuchMethodException) {
                // 目标类没这个方法，跳过
            } catch (_: ClassNotFoundException) {
                // 某些版本没这个 component，跳过
            } catch (t: Throwable) {
                Logger.e("MenuBuilderHook hook target fail: $target", t)
            }
        }
    }
}

interface OnMenuBuilder {
    val targetTypes: Array<String>
        get() = arrayOf(
            "com.tencent.mobileqq.aio.aiogift.AIOTroopGiftComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.anisticker.AIOAniStickerContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOArkContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOCenterArkContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.chain.ChainAniStickerContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.facebubble.AIOFaceBubbleContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.file.AIOFileContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.file.AIOOnlineFileContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.flashpic.AIOFlashPicContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.fold.AIOFoldContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.ickbreak.AIOIceBreakContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.leftswipearea.AIOLeftSwipeAreaComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.longmsg.AIOLongMsgContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.markdown.AIORichContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.markdown.AIOMarkdownContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.marketface.AIOMarketFaceComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.mask.AIOContentMaskComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.mix.AIOMixContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.msgaction.AIOMsgRecommendComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.msgfollow.AIOMsgFollowComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.msgreply.AIOMsgItemReplyComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.msgstatus.AIOMsgStatusComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.msgtail.AIOGeneralMsgTailContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.multifoward.AIOMultifowardContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.multipci.AIOMultiPicContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.nick.AIONickComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.poke.AIOPokeContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.position.AIOPositionMsgComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.prologue.AIOPrologueContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.select.AIOSelectComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.sysface.AIOSingleSysFaceContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.template.AIOTemplateMsgComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOUnsuportContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.timestamp.AIOTimestampComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.tofu.AIOTofuContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.video.AIOVideoContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.videochat.AIOVideoResultContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.zplan.AIOZPlanContentComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.graptips.common.CommonGrayTipsComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.graptips.revoke.RevokeGrayTipsComponent",
            "com.tencent.mobileqq.aio.msglist.holder.component.LocationShare.AIOLocationShareComponent",
            "com.tencent.mobileqq.aio.qwallet.AIOQWalletComponent",
            "com.tencent.mobileqq.aio.shop.AIOShopArkContentComponent",
            "com.tencent.qqnt.aio.sample.BusinessSampleContentComponent",
        )

    fun onGetMenu(
        aioMsgItem: Any,
        targetType: String,
        param: XC_MethodHook.MethodHookParam
    )
}