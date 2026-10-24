package io.github.rvlion.dgmusic.audio;

import io.github.rvlion.dgmusic.qq.Song;
import net.kyori.adventure.key.Key;

import java.util.UUID;

/**
 * 已转换并打包为资源包、可以直接推送给玩家的歌曲。
 *
 * @param id       资源包文件名与声音事件名所用的短 id
 * @param packId   资源包 UUID
 * @param sha1     资源包 SHA-1
 * @param soundKey 资源包中的声音事件
 */
public record PreparedTrack(Song song, String id, UUID packId, String sha1, Key soundKey) {
}
