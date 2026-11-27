/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.data.bean.internal

import com.google.gson.annotations.SerializedName

/**
 * @author Dr (dr@der.kim)
 * @date 2023/7/26 17:02
 */
internal class BeanGithubReleasesApi {
    val url: String = ""

    @SerializedName("assets_url")
    val assetsUrl: String = ""

    @SerializedName("upload_url")
    val uploadUrl: String = ""

    @SerializedName("html_url")
    val htmlUrl: String = ""
    val id: Int = 0
    val author: AuthorDTO? = null

    @SerializedName("node_id")
    val nodeId: String = ""

    @SerializedName("tag_name")
    val tagName: String = ""

    @SerializedName("target_commitish")
    val targetCommitish: String = ""
    val name: String = ""
    val draft: Boolean = false
    val prerelease: Boolean = false

    @SerializedName("created_at")
    val createdAt: String = ""

    @SerializedName("published_at")
    val publishedAt: String = ""
    val assets: List<AssetsDTO>? = null

    @SerializedName("tarball_url")
    val tarballUrl: String = ""

    @SerializedName("zipball_url")
    val zipballUrl: String = ""
    val body: String = ""

    @SerializedName("mentions_count")
    val mentionsCount: Int = 0
    val reactions: ReactionsDTO? = null


    class AuthorDTO {
        val login: String = ""
        val id: Int = 0

        @SerializedName("node_id")
        val nodeId: String = ""

        @SerializedName("avatar_url")
        val avatarUrl: String = ""

        @SerializedName("gravatar_id")
        val gravatarId: String = ""
        val url: String = ""

        @SerializedName("html_url")
        val htmlUrl: String = ""

        @SerializedName("followers_url")
        val followersUrl: String = ""

        @SerializedName("following_url")
        val followingUrl: String = ""

        @SerializedName("gists_url")
        val gistsUrl: String = ""

        @SerializedName("starred_url")
        val starredUrl: String = ""

        @SerializedName("subscriptions_url")
        val subscriptionsUrl: String = ""

        @SerializedName("organizations_url")
        val organizationsUrl: String = ""

        @SerializedName("repos_url")
        val reposUrl: String = ""

        @SerializedName("events_url")
        val eventsUrl: String = ""

        @SerializedName("received_events_url")
        val receivedEventsUrl: String = ""
        val type: String = ""

        @SerializedName("site_admin")
        val siteAdmin: Boolean = false
    }

    class ReactionsDTO {
        val url: String = ""

        @SerializedName("total_count")
        val totalCount: Int = 0

        @SerializedName("+1")
        val ok: Int = 0

        @SerializedName("-1")
        val no: Int = 0
        val laugh: Int = 0
        val hooray: Int = 0
        val confused: Int = 0
        val heart: Int = 0
        val rocket: Int = 0
        val eyes: Int = 0
    }

    class AssetsDTO {
        val url: String = ""
        val id: Int = 0

        @SerializedName("node_id")
        val nodeId: String = ""
        val name: String = ""
        val label: Any = ""
        val uploader: UploaderDTO? = null

        @SerializedName("content_type")
        val contentType: String = ""
        val state: String = ""
        val size: Int = 0

        @SerializedName("download_count")
        val downloadCount: Int = 0

        @SerializedName("created_at")
        val createdAt: String = ""

        @SerializedName("updated_at")
        val updatedAt: String = ""

        @SerializedName("browser_download_url")
        val browserDownloadUrl: String = ""

        var bytes: ByteArray? = null

        class UploaderDTO {
            val login: String = ""
            val id: Int = 0

            @SerializedName("node_id")
            val nodeId: String = ""

            @SerializedName("avatar_url")
            val avatarUrl: String = ""

            @SerializedName("gravatar_id")
            val gravatarId: String = ""
            val url: String = ""

            @SerializedName("html_url")
            val htmlUrl: String = ""

            @SerializedName("followers_url")
            val followersUrl: String = ""

            @SerializedName("following_url")
            val followingUrl: String = ""

            @SerializedName("gists_url")
            val gistsUrl: String = ""

            @SerializedName("starred_url")
            val starredUrl: String = ""

            @SerializedName("subscriptions_url")
            val subscriptionsUrl: String = ""

            @SerializedName("organizations_url")
            val organizationsUrl: String = ""

            @SerializedName("repos_url")
            val reposUrl: String = ""

            @SerializedName("events_url")
            val eventsUrl: String = ""

            @SerializedName("received_events_url")
            val receivedEventsUrl: String = ""
            val type: String = ""

            @SerializedName("site_admin")
            val siteAdmin: Boolean = false
        }
    }
}
