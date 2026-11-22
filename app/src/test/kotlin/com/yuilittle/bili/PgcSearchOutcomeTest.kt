package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PGC 搜索响应三分类与风控识别。
 * 实测背景：B 站对 wbi/search/type 的风控会返回 HTTP 200 + code=0 + data.v_voucher（静默清空），
 * 老接口零命中返回 numResults=0 且无 result 键，两者必须区分，否则风控被当成"搜不到"。
 */
class PgcSearchOutcomeTest {

    @Test
    fun classifySearchOutcomeDistinguishesEmptyHitFromGaiaVerification() {
        // 正常命中：有 result 键
        assertEquals(
            BiliApi.SearchOutcome.HAS_RESULT,
            BiliApi.classifySearchOutcome(voucher = "", numResults = 1, hasResultKey = true)
        )
        // 正常零命中：numResults=0 且无 result 键（实测"进击的巨人"形态），不算风控
        assertEquals(
            BiliApi.SearchOutcome.EMPTY,
            BiliApi.classifySearchOutcome(voucher = "", numResults = 0, hasResultKey = false)
        )
        // 风控验证码：HTTP 200 + code=0 但 data 只带 v_voucher（实测 app 搜不到的根因形态）
        assertEquals(
            BiliApi.SearchOutcome.GAIA_VERIFICATION,
            BiliApi.classifySearchOutcome(voucher = "user1:xxx", numResults = -1, hasResultKey = false)
        )
        // 结构异常：无 voucher、无 numResults、无 result，按风控处理触发重试
        assertEquals(
            BiliApi.SearchOutcome.GAIA_VERIFICATION,
            BiliApi.classifySearchOutcome(voucher = "", numResults = -1, hasResultKey = false)
        )
    }

    @Test
    fun isRequestBannedCoversGaiaVoucherAndHttpAndBusiness412() {
        fun banned(message: String) = BiliApi.isRequestBannedForTest(IllegalStateException(message))
        // GAIA 验证码（v_voucher 前缀，由 searchPgc 抛出）
        assert(banned("v_voucher:user1:abc123"))
        // 业务码 -412 与中文拦截文案
        assert(banned("请求被拦截"))
        assert(banned("搜索接口异常（HTTP 412）"))
        // 英文风控文案与风险/过快
        assert(banned("request was banned"))
        assert(banned("请求过快，请稍后再试"))
        // 普通网络错误不触发重试
        assert(!banned("网络请求失败"))
        assert(!banned("搜索接口没有返回内容（HTTP 502）"))
    }
}
