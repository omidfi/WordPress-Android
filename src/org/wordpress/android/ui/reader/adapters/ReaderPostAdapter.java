package org.wordpress.android.ui.reader.adapters;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderPostList;
import org.wordpress.android.ui.reader.ReaderActivity.ReaderPostListType;
import org.wordpress.android.ui.reader.ReaderActivityLauncher;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.ReaderPostListFragment.OnTagSelectedListener;
import org.wordpress.android.ui.reader.ReaderUtils;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderBlogActions;
import org.wordpress.android.ui.reader.actions.ReaderPostActions;
import org.wordpress.android.util.AniUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.DateTimeUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.FormatUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.stats.AnalyticsTracker;
import org.wordpress.android.widgets.WPNetworkImageView;

import java.lang.ref.WeakReference;

/**
 * adapter for list of posts in a specific tag
 */
public class ReaderPostAdapter extends BaseAdapter {
    private String mCurrentTag;
    private long mCurrentBlogId;

    private final int mPhotonWidth;
    private final int mPhotonHeight;
    private final int mAvatarSz;
    private final int mMarginLarge;

    private final float mRowAnimationFromYDelta;
    private final int mRowAnimationDuration;
    private boolean mCanRequestMorePosts = false;
    private boolean mAnimateRows = false;
    private boolean mIsFlinging = false;

    private final LayoutInflater mInflater;
    private final WeakReference<Context> mWeakContext;
    private final ReaderPostListType mPostListType;
    private ReaderPostList mPosts = new ReaderPostList();

    private OnTagSelectedListener mOnTagSelectedListener;
    private final ReaderActions.RequestReblogListener mReblogListener;
    private final ReaderActions.DataLoadedListener mDataLoadedListener;
    private final ReaderActions.DataRequestedListener mDataRequestedListener;

    private final boolean mEnableImagePreload;
    private int mLastPreloadPos = -1;
    private static final int PRELOAD_OFFSET = 2;

    public ReaderPostAdapter(Context context,
                             ReaderPostListType postListType,
                             ReaderActions.RequestReblogListener reblogListener,
                             ReaderActions.DataLoadedListener dataLoadedListener,
                             ReaderActions.DataRequestedListener dataRequestedListener) {
        super();

        mWeakContext = new WeakReference<Context>(context);
        mInflater = LayoutInflater.from(context);

        mPostListType = postListType;
        mReblogListener = reblogListener;
        mDataLoadedListener = dataLoadedListener;
        mDataRequestedListener = dataRequestedListener;

        mAvatarSz = context.getResources().getDimensionPixelSize(R.dimen.avatar_sz_medium);
        mMarginLarge = context.getResources().getDimensionPixelSize(R.dimen.margin_large);

        int displayWidth = DisplayUtils.getDisplayPixelWidth(context);
        int displayHeight = DisplayUtils.getDisplayPixelHeight(context);

        int listMargin = context.getResources().getDimensionPixelSize(R.dimen.reader_list_margin);
        mPhotonWidth = displayWidth - (listMargin * 2);
        mPhotonHeight = context.getResources().getDimensionPixelSize(R.dimen.reader_featured_image_height);

        // when animating rows in, start from this y-position near the bottom using medium animation duration
        mRowAnimationFromYDelta = displayHeight - (displayHeight / 6);
        mRowAnimationDuration = context.getResources().getInteger(android.R.integer.config_mediumAnimTime);

        // enable preloading of images
        mEnableImagePreload = true;
    }

    private Context getContext() {
        return mWeakContext.get();
    }

    public void setOnTagSelectedListener(OnTagSelectedListener listener) {
        mOnTagSelectedListener = listener;
    }

    ReaderPostListType getPostListType() {
        return (mPostListType != null ? mPostListType : ReaderPostListType.getDefaultType());
    }

    public String getCurrentTag() {
        return StringUtils.notNullStr(mCurrentTag);
    }

    // used when the viewing tagged posts
    public void setCurrentTag(String tagName) {
        tagName = StringUtils.notNullStr(tagName);
        if (mCurrentTag == null || !mCurrentTag.equals(tagName)) {
            mCurrentTag = tagName;
            reload(false);
        }
    }

    // used when the list type is ReaderPostListType.BLOG_PREVIEW
    public void setCurrentBlog(long blogId) {
        if (blogId != mCurrentBlogId) {
            mCurrentBlogId = blogId;
            reload(false);
        }
    }

    private void clear() {
        mLastPreloadPos = -1;
        if (!mPosts.isEmpty()) {
            mPosts.clear();
            notifyDataSetChanged();
        }
    }

    /*
     * briefly animate the appearance of new rows when reloading
     */
    private void enableRowAnimation() {
        mAnimateRows = true;
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mAnimateRows = false;
            }
        }, 1000);
    }

    public void refresh() {
        //clear(); <-- don't do this, causes LoadPostsTask to always think all posts are new
        loadPosts();
    }

    public void reload(boolean animate) {
        if (animate)
            enableRowAnimation();
        clear();
        loadPosts();
    }

    /*
     * reload a single post
     */
    public void reloadPost(ReaderPost post) {
        int index = mPosts.indexOfPost(post);
        if (index == -1)
            return;

        final ReaderPost updatedPost = ReaderPostTable.getPost(post.blogId, post.postId);
        if (updatedPost==null)
            return;

        mPosts.set(index, updatedPost);
        notifyDataSetChanged();
    }

    /*
     * ensures that the follow status of each post in the list reflects what is currently
     * stored in the reader post table
     */
    public void checkFollowStatusForAllPosts() {
        if (ReaderPostTable.checkFollowStatusOnPosts(mPosts)) {
            notifyDataSetChanged();
        }
    }

    /*
     * sets the follow status of each post in the passed blog
     */
    public void updateFollowStatusOnPostsForBlog(long blogId, String blogUrl, boolean followStatus) {
        if (isEmpty()) {
            return;
        }

        boolean hasBlogId = (blogId != 0);
        boolean hasBlogUrl = !TextUtils.isEmpty(blogUrl);
        if (!hasBlogId && !hasBlogUrl) {
            return;
        }

        boolean isChanged = false;
        for (ReaderPost post: mPosts) {
            boolean isMatched = (hasBlogId ? (blogId == post.blogId) : blogUrl.equals(post.getBlogUrl()));
            if (isMatched) {
                post.isFollowedByCurrentUser = followStatus;
                isChanged = true;
            }
        }
        if (isChanged) {
            notifyDataSetChanged();
        }
    }

    private void loadPosts() {
        if (mIsTaskRunning) {
            AppLog.w(T.READER, "reader posts task already running");
        }
        new LoadPostsTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public int getCount() {
        return mPosts.size();
    }

    @Override
    public Object getItem(int position) {
        return mPosts.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getView(final int position, View convertView, final ViewGroup parent) {
        final ReaderPost post = (ReaderPost) getItem(position);
        final PostViewHolder holder;

        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.reader_listitem_post_excerpt, parent, false);
            holder = new PostViewHolder(convertView, getPostListType());
            convertView.setTag(holder);
        } else {
            holder = (PostViewHolder) convertView.getTag();
        }

        holder.txtTitle.setText(post.getTitle());
        holder.txtDate.setText(DateTimeUtils.javaDateToTimeSpan(post.getDatePublished()));

        // post header (avatar, blog name and follow button) only appears when showing tagged posts
        if (getPostListType().isTagType()) {
            holder.imgAvatar.setImageUrl(post.getPostAvatarForDisplay(mAvatarSz), WPNetworkImageView.ImageType.AVATAR);
            if (post.hasBlogName()) {
                holder.txtBlogName.setText(post.getBlogName());
            } else if (post.hasAuthorName()) {
                holder.txtBlogName.setText(post.getAuthorName());
            } else {
                holder.txtBlogName.setText(null);
            }

            // follow/following - supported by both wp and non-wp (rss) posts
            ReaderUtils.showFollowStatus(holder.txtFollow, post.isFollowedByCurrentUser);
            holder.txtFollow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleFollow(holder, position, post);
                }
            });

            // tapping header shows blog preview unless this post is from an external feed
            if (!post.isExternal) {
                holder.layoutPostHeader.setEnabled(true);
                holder.layoutPostHeader.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ReaderActivityLauncher.showReaderBlogPreview(getContext(), post.blogId, post.getBlogUrl());
                    }
                });
            } else {
                holder.layoutPostHeader.setOnClickListener(null);
                holder.layoutPostHeader.setEnabled(false);
            }
        }

        if (post.hasExcerpt()) {
            holder.txtText.setVisibility(View.VISIBLE);
            holder.txtText.setText(post.getExcerpt());
        } else {
            holder.txtText.setVisibility(View.GONE);
        }

        final int titleMargin;
        if (post.hasFeaturedImage()) {
            final String imageUrl = post.getFeaturedImageForDisplay(mPhotonWidth, mPhotonHeight);
            // skip loading image if the imageView is already tagged with this url
            if (!ReaderUtils.viewHasTag(holder.imgFeatured, imageUrl)) {
                holder.imgFeatured.setImageUrl(imageUrl, WPNetworkImageView.ImageType.PHOTO);
                holder.imgFeatured.setTag(imageUrl);
            }
            holder.imgFeatured.setVisibility(View.VISIBLE);
            titleMargin = mMarginLarge;
        } else if (post.hasFeaturedVideo()) {
            holder.imgFeatured.setVideoUrl(post.postId, post.getFeaturedVideo());
            holder.imgFeatured.setVisibility(View.VISIBLE);
            holder.imgFeatured.setTag(null);
            titleMargin = mMarginLarge;
        } else {
            holder.imgFeatured.setVisibility(View.GONE);
            holder.imgFeatured.setTag(null);
            titleMargin = (holder.layoutPostHeader.getVisibility() == View.VISIBLE ? 0 : mMarginLarge);
        }

        // set the top margin of the title based on whether there's a featured image and post header
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) holder.txtTitle.getLayoutParams();
        params.topMargin = titleMargin;

        // show the best tag for this post
        final String tagToDisplay = post.getTagForDisplay(mCurrentTag);
        if (!TextUtils.isEmpty(tagToDisplay)) {
            holder.txtTag.setText(tagToDisplay);
            holder.txtTag.setVisibility(View.VISIBLE);
            holder.txtTag.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mOnTagSelectedListener != null) {
                        mOnTagSelectedListener.onTagSelected(tagToDisplay);
                    }
                }
            });
        } else {
            holder.txtTag.setVisibility(View.GONE);
        }

        // likes, comments & reblogging - supported by wp posts only
        if (post.isWP()) {
            showLikeStatus(holder.imgBtnLike, post.isLikedByCurrentUser);
            holder.imgBtnComment.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (parent instanceof ListView) {
                        ListView listView = (ListView) parent;
                        // the base listView onItemClick includes the header count in the position,
                        // so do the same here
                        int index = position + listView.getHeaderViewsCount();
                        listView.performItemClick(holder.imgBtnComment, index, getItemId(position));
                    }
                }
            });

            holder.imgBtnLike.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleLike(holder, position, post);
                }
            });

            showReblogStatus(holder.imgBtnReblog, post.isRebloggedByCurrentUser);
            if (!post.isRebloggedByCurrentUser && post.isWP()) {
                holder.imgBtnReblog.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AniUtils.zoomAction(holder.imgBtnReblog);
                        if (mReblogListener != null) {
                            mReblogListener.onRequestReblog(post);
                        }
                    }
                });
            }

            holder.imgBtnLike.setVisibility(View.VISIBLE);
            holder.imgBtnComment.setVisibility(View.VISIBLE);
            holder.imgBtnReblog.setVisibility(View.VISIBLE);
            showCounts(holder, post);
        } else {
            holder.imgBtnLike.setVisibility(View.INVISIBLE);
            holder.imgBtnComment.setVisibility(View.INVISIBLE);
            holder.imgBtnReblog.setVisibility(View.INVISIBLE);
            holder.txtLikeCount.setVisibility(View.GONE);
            holder.txtCommentCount.setVisibility(View.GONE);
        }

        // animate the appearance of this row while new posts are being loaded
        if (mAnimateRows) {
            animateRow(convertView);
        }

        // if we're nearing the end of the posts, fire request to load more
        if (mCanRequestMorePosts && mDataRequestedListener != null && (position >= getCount()-1)) {
            mDataRequestedListener.onRequestData(ReaderActions.RequestDataAction.LOAD_OLDER);
        }

        // if image preload is enabled, preload images in the post PRELOAD_OFFSET positions ahead of this one
        if (mEnableImagePreload && position > (mLastPreloadPos - PRELOAD_OFFSET)) {
            preloadPostImages(position + PRELOAD_OFFSET);
        }

        return convertView;
    }

    /*
     * shows like & comment count
     */
    private void showCounts(final PostViewHolder holder, final ReaderPost post) {
        if (post.numLikes > 0) {
            holder.txtLikeCount.setText(FormatUtils.formatInt(post.numLikes));
            holder.txtLikeCount.setVisibility(View.VISIBLE);
        } else {
            holder.txtLikeCount.setVisibility(View.GONE);
        }

        if (post.numReplies > 0) {
            holder.txtCommentCount.setText(FormatUtils.formatInt(post.numReplies));
            holder.txtCommentCount.setVisibility(View.VISIBLE);
            // note that the comment icon is shown here even if comments are now closed since
            // the post has existing comments
            holder.imgBtnComment.setVisibility(View.VISIBLE);
        } else {
            holder.txtCommentCount.setVisibility(View.GONE);
            holder.imgBtnComment.setVisibility(post.isCommentsOpen ? View.VISIBLE : View.GONE);
        }
    }

    /*
     * animate in the passed listView item
     */
    private final DecelerateInterpolator mRowInterpolator = new DecelerateInterpolator();
    private void animateRow(View view) {
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, mRowAnimationFromYDelta, 0f);
        animator.setDuration(mRowAnimationDuration);
        animator.setInterpolator(mRowInterpolator);
        animator.start();
    }

    private static class PostViewHolder {
        private final TextView txtTitle;
        private final TextView txtText;
        private final TextView txtBlogName;
        private final TextView txtDate;
        private final TextView txtFollow;
        private final TextView txtTag;

        private final TextView txtLikeCount;
        private final TextView txtCommentCount;

        private final ImageView imgBtnLike;
        private final ImageView imgBtnComment;
        private final ImageView imgBtnReblog;

        private final WPNetworkImageView imgFeatured;
        private final WPNetworkImageView imgAvatar;

        private final ViewGroup layoutPostHeader;

        PostViewHolder(View view, ReaderPostListType postListType) {
            txtTitle = (TextView) view.findViewById(R.id.text_title);
            txtText = (TextView) view.findViewById(R.id.text_excerpt);
            txtBlogName = (TextView) view.findViewById(R.id.text_blog_name);
            txtDate = (TextView) view.findViewById(R.id.text_date);
            txtFollow = (TextView) view.findViewById(R.id.text_follow);
            txtTag = (TextView) view.findViewById(R.id.text_tag);

            txtCommentCount = (TextView) view.findViewById(R.id.text_comment_count);
            txtLikeCount = (TextView) view.findViewById(R.id.text_like_count);

            imgFeatured = (WPNetworkImageView) view.findViewById(R.id.image_featured);
            imgAvatar = (WPNetworkImageView) view.findViewById(R.id.image_avatar);

            imgBtnLike = (ImageView) view.findViewById(R.id.image_like_btn);
            imgBtnComment = (ImageView) view.findViewById(R.id.image_comment_btn);
            imgBtnReblog = (ImageView) view.findViewById(R.id.image_reblog_btn);

            layoutPostHeader = (ViewGroup) view.findViewById(R.id.layout_post_header);

            // hide the post header (avatar, blog name & follow button) if we're showing posts
            // in a specific blog
            if (postListType.equals(ReaderPostListType.BLOG_PREVIEW)) {
                layoutPostHeader.setVisibility(View.GONE);
            }
        }
    }

    /*
     * triggered when user taps the like button (textView)
     */
    private void toggleLike(PostViewHolder holder, int position, ReaderPost post) {
        // start animation immediately so user knows they did something
        AniUtils.zoomAction(holder.imgBtnLike);

        boolean isAskingToLike = !post.isLikedByCurrentUser;
        if (!ReaderPostActions.performLikeAction(post, isAskingToLike)) {
            return;
        }

        if (isAskingToLike) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.READER_LIKED_ARTICLE);
        }

        // update post in array and on screen
        ReaderPost updatedPost = ReaderPostTable.getPost(post.blogId, post.postId);
        mPosts.set(position, updatedPost);
        showLikeStatus(holder.imgBtnLike, updatedPost.isLikedByCurrentUser);
        showCounts(holder, post);
    }

    private void showLikeStatus(ImageView imgBtnLike, boolean isLikedByCurrentUser) {
        if (isLikedByCurrentUser != imgBtnLike.isSelected())
            imgBtnLike.setSelected(isLikedByCurrentUser);
    }

    /*
     * triggered when user taps the follow button
     */
    private void toggleFollow(final PostViewHolder holder, int position, ReaderPost post) {
        AniUtils.zoomAction(holder.txtFollow);
        final boolean isAskingToFollow = !post.isFollowedByCurrentUser;

        ReaderActions.ActionListener actionListener = new ReaderActions.ActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                if (!succeeded && getContext() != null) {
                    int resId = (isAskingToFollow ? R.string.reader_toast_err_follow_blog : R.string.reader_toast_err_unfollow_blog);
                    ToastUtils.showToast(getContext(), resId);
                    ReaderUtils.showFollowStatus(holder.txtFollow, !isAskingToFollow);
                }
            }
        };

        if (!ReaderBlogActions.performFollowAction(post, isAskingToFollow, actionListener)) {
            return;
        }

        ReaderPost updatedPost = ReaderPostTable.getPost(post.blogId, post.postId);
        if (updatedPost != null) {
            mPosts.set(position, updatedPost);
        }

        ReaderUtils.showFollowStatus(holder.txtFollow, isAskingToFollow);
        updateFollowStatusOnPostsForBlog(post.blogId, post.getBlogUrl(), isAskingToFollow);
    }

    private void showReblogStatus(ImageView imgBtnReblog, boolean isRebloggedByCurrentUser) {
        if (isRebloggedByCurrentUser != imgBtnReblog.isSelected()) {
            imgBtnReblog.setSelected(isRebloggedByCurrentUser);
        }
        if (isRebloggedByCurrentUser) {
            imgBtnReblog.setOnClickListener(null);
        }
    }

    /*
     * AsyncTask to load posts in the current tag
     */
    private boolean mIsTaskRunning = false;
    private class LoadPostsTask extends AsyncTask<Void, Void, Boolean> {
        ReaderPostList tmpPosts;
        @Override
        protected void onPreExecute() {
            mIsTaskRunning = true;
        }
        @Override
        protected void onCancelled() {
            mIsTaskRunning = false;
        }
        @Override
        protected Boolean doInBackground(Void... params) {
            final int numExisting;
            switch (getPostListType()) {
                 case TAG_PREVIEW: case TAG_FOLLOWED:
                    tmpPosts = ReaderPostTable.getPostsWithTag(mCurrentTag, ReaderConstants.READER_MAX_POSTS_TO_DISPLAY);
                    numExisting = ReaderPostTable.getNumPostsWithTag(mCurrentTag);
                    break;
                case BLOG_PREVIEW:
                    tmpPosts = ReaderPostTable.getPostsInBlog(mCurrentBlogId, ReaderConstants.READER_MAX_POSTS_TO_DISPLAY);
                    numExisting = ReaderPostTable.getNumPostsInBlog(mCurrentBlogId);
                    break;
                default:
                    return false;
            }

            if (mPosts.isSameList(tmpPosts)) {
                return false;
            }

            // if we're not already displaying the max # posts, enable requesting more when
            // the user scrolls to the end of the list
            mCanRequestMorePosts = (numExisting < ReaderConstants.READER_MAX_POSTS_TO_DISPLAY);

            // pre-calc avatar URLs, featured image URLs, display tag, and pubDates in each
            // post - these values are all cached by the post after the first time they're
            // computed, so calling these getters ensures the values are immediately available
            // when accessed from getView
            for (ReaderPost post: tmpPosts) {
                post.getPostAvatarForDisplay(mAvatarSz);
                post.getFeaturedImageForDisplay(mPhotonWidth, mPhotonHeight);
                post.getDatePublished();
                post.getTagForDisplay(mCurrentTag);
            }

            return true;
        }
        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                mPosts = (ReaderPostList)(tmpPosts.clone());

                // preload images in the first few posts
                if (mEnableImagePreload && mPosts.size() >= PRELOAD_OFFSET) {
                    for (int i = 0; i <= PRELOAD_OFFSET; i++) {
                        preloadPostImages(i);
                    }
                }

                notifyDataSetChanged();
            }

            if (mDataLoadedListener != null) {
                mDataLoadedListener.onDataLoaded(isEmpty());
            }

            mIsTaskRunning = false;
        }
    }

    /*
     * called from ReaderPostListFragment when user starts/ends listview fling
     */
    public void setIsFlinging(boolean isFlinging) {
        mIsFlinging = isFlinging;
    }

    /**
     *  preload images for the post at the passed position
     */
    private void preloadPostImages(final int position) {
        if (position >= mPosts.size() || position < 0) {
            return;
        }

        mLastPreloadPos = position;

        // skip if listview is in a fling (note that we still set mLastPreloadPos above)
        if (mIsFlinging) {
            return;
        }

        final ReaderPost post = mPosts.get(position);
        if (post.hasFeaturedImage()) {
            preloadImage(post.getFeaturedImageForDisplay(mPhotonWidth, mPhotonHeight));
        }
        if (post.hasPostAvatar()) {
            preloadImage(post.getPostAvatarForDisplay(mAvatarSz));
        }
    }

    /*
     * preload the passed image if it's not already cached
     */
    private void preloadImage(final String imageUrl) {
        // skip if image is already in the LRU memory cache
        if (WordPress.imageLoader.isCached(imageUrl, 0, 0)) {
            return;
        }

        // skip if image is already in the disk cache
        if (WordPress.requestQueue.getCache().get(imageUrl) != null) {
            return;
        }

        // note that mImagePreloadListener doesn't do anything, but it's required by volley
        WordPress.imageLoader.get(imageUrl, mImagePreloadListener);
    }

    private final ImageLoader.ImageListener mImagePreloadListener = new ImageLoader.ImageListener() {
        @Override
        public void onResponse(ImageLoader.ImageContainer imageContainer, boolean isImmediate) {
            // nop
        }
        @Override
        public void onErrorResponse(VolleyError volleyError) {
            AppLog.e(T.READER, volleyError);
        }
    };
}
