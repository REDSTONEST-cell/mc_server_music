package io.github.rvlion.dgmusic.qq;

/**
 * QQ音乐搜索结果中的一首歌。
 *
 * @param mid             歌曲 mid
 * @param mediaMid        音频文件 mid，拼接播放文件名时使用
 * @param name            歌名
 * @param singers         歌手（多个用 / 分隔）
 * @param album           专辑名
 * @param durationSeconds 时长（秒）
 * @param vip             是否为付费/会员歌曲
 */
public record Song(String mid, String mediaMid, String name, String singers, String album,
                   int durationSeconds, boolean vip) {

    public String displayName() {
        return singers.isEmpty() ? name : name + " - " + singers;
    }

    public String formattedDuration() {
        return String.format("%d:%02d", durationSeconds / 60, durationSeconds % 60);
    }
}
