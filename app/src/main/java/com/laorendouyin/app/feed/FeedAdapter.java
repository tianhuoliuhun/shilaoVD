package com.laorendouyin.app.feed;

// 源自 DKVideoPlayer demo 的 Tiktok2Adapter（Apache-2.0，Copyright Doikki）。
// myDVQ 改造：**删除全部互动**（点赞/评论/收藏/分享/作者头像）与标题文字，
// item 里只剩「播放器容器 + 封面」，视图复用池与预加载任务绑定照旧保留。
// License: Apache-2.0（见 lib/LICENSE-DKVideoPlayer.txt）

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import com.bumptech.glide.Glide;
import com.laorendouyin.app.R;
import com.laorendouyin.app.cache.PreloadManager;

import java.util.ArrayList;
import java.util.List;

public class FeedAdapter extends PagerAdapter {

    /** View 缓存池：从 ViewPager 移除的 item 存这里复用（沿用 demo 方案） */
    private final List<View> mViewPool = new ArrayList<>();

    private final List<FeedVideo> mVideos;

    public FeedAdapter(List<FeedVideo> videos) {
        this.mVideos = videos;
    }

    @Override
    public int getCount() {
        return mVideos == null ? 0 : mVideos.size();
    }

    /**
     * 数据变更后的位置映射（真机踩坑：不覆写它 = notifyDataSetChanged 对已有页面不重绑，
     * 旧封面留在页面上、播放器却播新列表的 URL = 内容与画面不一致）。
     * 按条目身份追踪：条目还在新列表 → 返回新位置；不在（列表被替换）→ POSITION_NONE 重建。
     */
    @Override
    public int getItemPosition(@NonNull Object object) {
        View view = (View) object;
        Object tag = view.getTag();
        if (!(tag instanceof ViewHolder)) return POSITION_NONE;
        FeedVideo item = ((ViewHolder) tag).mItem;
        int idx = mVideos == null ? -1 : mVideos.indexOf(item);
        return idx >= 0 ? idx : POSITION_NONE;
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object o) {
        return view == o;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        Context context = container.getContext();
        // 边界护栏：数据变更与 populate 竞态时 position 可能瞬时越界
        final int pos = Math.min(position, Math.max(0, mVideos.size() - 1));
        View view = null;
        if (mViewPool.size() > 0) {
            view = mViewPool.get(0);
            mViewPool.remove(0);
        }

        final ViewHolder viewHolder;
        if (view == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_feed, container, false);
            viewHolder = new ViewHolder(view);
        } else {
            viewHolder = (ViewHolder) view.getTag();
        }

        final FeedVideo item = mVideos.get(pos);
        // 复用视图可能残留上一页的播放器容器内容 → 清掉（防串页）
        viewHolder.mPlayerContainer.removeAllViews();
        // 封面回位（复用视图可能停在上一条"已播放→封面已隐藏"的状态）
        viewHolder.mThumb.setVisibility(View.VISIBLE);
        // 开始预加载
        PreloadManager.getInstance(context).addPreloadTask(item.playUrl, position);
        Glide.with(context)
                .load(item.coverUrl)
                .placeholder(android.R.color.black)
                .listener(imageLog("封面", item.awemeId))
                .into(viewHolder.mThumb);

        viewHolder.mPosition = pos;
        viewHolder.mItem = item;
        container.addView(view);
        return view;
    }

    /**
     * 图片加载的成功/失败回调，只为了**能在日志里取证**。
     * "没有报错"不等于"加载成功"—— Glide 失败是静默的，界面上看起来只是"没图"。
     */
    private static com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>
    imageLog(final String what, final String awemeId) {
        return new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
            @Override
            public boolean onLoadFailed(@androidx.annotation.Nullable
                                        com.bumptech.glide.load.engine.GlideException e,
                                        Object model,
                                        @NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                        boolean isFirstResource) {
                com.laorendouyin.app.util.AppLog.i("feed", what + "加载失败 aweme=" + awemeId
                        + " err=" + (e == null ? "?" : e.getMessage()));
                return false;   // 交回 Glide 继续走 error 占位图
            }

            @Override
            public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                           Object model,
                                           @NonNull com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                           @NonNull com.bumptech.glide.load.DataSource dataSource,
                                           boolean isFirstResource) {
                com.laorendouyin.app.util.AppLog.i("feed", what + "加载成功 aweme=" + awemeId
                        + " from=" + dataSource);
                return false;
            }
        };
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        View itemView = (View) object;
        container.removeView(itemView);
        // ⚠️ PagerAdapter 契约：notifyDataSetChanged 后回收旧页面时，传入的 position 是
        // 数据变更前的旧序号——用 mVideos.get(position) 取数必然越界/错位。
        // 数据一律从视图标签取（原基座真机 4 次 IndexOutOfBounds 闪退的根因）。
        Object tag = itemView.getTag();
        if (tag instanceof ViewHolder) {
            FeedVideo item = ((ViewHolder) tag).mItem;
            if (item != null) {
                PreloadManager.getInstance(container.getContext()).removePreloadTask(item.playUrl);
            }
        }
        mViewPool.add(itemView);
    }

    /** 极简 ViewHolder：只有封面与播放器容器，没有任何可点控件 */
    public static class ViewHolder {

        public int mPosition;
        /** 当前绑定的数据条目（getItemPosition 按它追踪位置，防数据错位） */
        public FeedVideo mItem;
        public ImageView mThumb;
        public TikTokView mTikTokView;
        public FrameLayout mPlayerContainer;

        ViewHolder(View itemView) {
            mTikTokView = itemView.findViewById(R.id.tiktok_view);
            mThumb = mTikTokView.thumb();
            mPlayerContainer = itemView.findViewById(R.id.container);
            itemView.setTag(this);
        }
    }
}
