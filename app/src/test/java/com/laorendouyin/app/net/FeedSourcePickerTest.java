package com.laorendouyin.app.net;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 内容源轮询 + 失败降级的单测（纯逻辑）。 */
public class FeedSourcePickerTest {

    private static final long MIN = 60_000L;

    /** 起点按调用轮转：第一次从 a 开始，第二次从 b 开始 */
    @Test
    public void startingPoint_rotatesAcrossCalls() {
        FeedSourcePicker p = new FeedSourcePicker(new String[]{"a", "b", "c"});
        assertEquals(Arrays.asList("a", "b", "c"), p.candidates(0));
        assertEquals(Arrays.asList("b", "c", "a"), p.candidates(0));
        assertEquals(Arrays.asList("c", "a", "b"), p.candidates(0));
    }

    /** 失败的源进入冷却后，排到最后，优先尝试其它源 */
    @Test
    public void failedSource_movesToEndDuringCooldown() {
        FeedSourcePicker p = new FeedSourcePicker(new String[]{"a", "b", "c"});
        p.markFailed("a", 0);
        List<String> c = p.candidates(1 * MIN);   // 起点 a，但 a 冷却中
        assertEquals(Arrays.asList("b", "c", "a"), c);
    }

    /** 冷却期过后，源恢复正常顺序 */
    @Test
    public void failedSource_recoversAfterCooldown() {
        FeedSourcePicker p = new FeedSourcePicker(new String[]{"a", "b"});
        p.markFailed("a", 0);
        List<String> c = p.candidates(FeedSourcePicker.COOLDOWN_MS + 1);
        assertEquals(Arrays.asList("a", "b"), c);
    }

    /** 请求成功会立刻解除冷却 */
    @Test
    public void markOk_clearsCooldownImmediately() {
        FeedSourcePicker p = new FeedSourcePicker(new String[]{"a", "b"});
        p.markFailed("a", 0);
        p.markOk("a");
        assertEquals(Arrays.asList("a", "b"), p.candidates(1 * MIN));
    }

    /** 单次最多返回 MAX_ATTEMPTS 个候选，避免一次刷新轮番重试所有源 */
    @Test
    public void candidates_capped() {
        FeedSourcePicker p = new FeedSourcePicker(new String[]{"a", "b", "c", "d", "e"});
        assertEquals(FeedSourcePicker.MAX_ATTEMPTS, p.candidates(0).size());
    }

    /** 全部源都在冷却中时，仍然返回候选，而不是一个都不试 */
    @Test
    public void allCooling_stillReturnsCandidates() {
        FeedSourcePicker p = new FeedSourcePicker(new String[]{"a", "b"});
        p.markFailed("a", 0);
        p.markFailed("b", 0);
        List<String> c = p.candidates(1 * MIN);
        assertEquals(2, c.size());
    }

    /** 未知的源名不影响状态（不抛异常、不误伤其它源） */
    @Test
    public void unknownSource_isIgnored() {
        FeedSourcePicker p = new FeedSourcePicker(new String[]{"a", "b"});
        p.markFailed("zzz", 0);
        p.markOk("zzz");
        assertEquals(2, p.candidates(1 * MIN).size());
    }

    /** 无源时（配置为空）候选为空，由调用方兜底成推荐流 */
    @Test
    public void emptySources_returnsNothing() {
        FeedSourcePicker p = new FeedSourcePicker(new String[0]);
        assertEquals(0, p.candidates(0).size());
    }
}
