package com.silencedut.diffadapter;

import android.os.Handler;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.recyclerview.extensions.AsyncDifferConfig;
import android.support.v7.util.AdapterListUpdateCallback;
import android.support.v7.util.DiffUtil;
import android.support.v7.util.ListUpdateCallback;
import android.util.Log;

import com.silencedut.diffadapter.data.BaseMutableData;
import com.silencedut.diffadapter.utils.ListChangedCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author SilenceDut
 * @date 2018/12/19
 */
class AsyncListUpdateDiffer<T extends BaseMutableData> {
    private static final String TAG = "AsyncListUpdateDiffer";
    private final ListUpdateCallback mUpdateCallback;
    private final AsyncDifferConfig<T> mConfig;
    private final ListChangedCallback<T> mListChangedCallback;
    @Nullable
    private List<T> mOldList;
    private long mMaxScheduledGeneration;
    private long mCanSyncTime = 0;
    private Set<Long> mGenerations = new HashSet<>();
    static final int DELAY_STEP = 5;
    private Handler mDiffHandler;

    AsyncListUpdateDiffer(@NonNull DiffAdapter adapter, @NonNull ListChangedCallback<T> listChangedCallback,
                          @NonNull DiffUtil.ItemCallback<T> diffCallback) {
        this.mDiffHandler = adapter.mDiffHandler;
        this.mUpdateCallback = new AdapterListUpdateCallback(adapter);
        this.mConfig = new AsyncDifferConfig.Builder<>(diffCallback).build();
        this.mListChangedCallback = listChangedCallback;
        updateCurrentList(new ArrayList<T>());
    }

    void submitList(@Nullable final List<T> newList) {
        final long runGeneration = ++this.mMaxScheduledGeneration;
        mGenerations.add(runGeneration);
        Log.d(TAG, "latchList submitList  runGeneration add :" + runGeneration + ";;size" + mGenerations.size());
        // 新旧数据列表 不能是同一个对象
        if (newList != this.mOldList) {
            if (newList == null) {
                int countRemoved = this.mOldList.size();
                syncOldList(null);
                updateCurrentList(new ArrayList<T>());
                this.mUpdateCallback.onRemoved(0, countRemoved);
                mGenerations.remove(runGeneration);
                Log.d(TAG, "latchList submitList newList == null runGeneration :" + runGeneration + ";;size" +
                        mGenerations.size());
            } else if (this.mOldList == null) {
                syncOldList(newList);
                // 第一次submitList更新数据时，更新同步时间
                updateSyncTime(newList);
                updateCurrentList(new ArrayList<>(newList));
                this.mUpdateCallback.onInserted(0, newList.size());
                mGenerations.remove(runGeneration);
                Log.d(TAG, "latchList submitList mOldList == null runGeneration :" + runGeneration + ";;size" +
                        mGenerations.size());
            } else {
                // 对新旧数据进行diff计算
                doDiff(newList, runGeneration);
            }
        }
    }

    private void doDiff(@NonNull final List<T> newList, final long runGeneration) {

        if (this.mOldList == null) {
            return;
        }

        final List<T> oldList = new ArrayList<>(this.mOldList);

        this.mConfig.getBackgroundThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                // 差分计算结果
                final DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                    @Override
                    public int getOldListSize() {
                        return oldList.size();
                    }

                    @Override
                    public int getNewListSize() {
                        return newList.size();
                    }

                    @Override
                    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {

                        T oldItem = oldList.get(oldItemPosition);
                        T newItem = newList.get(newItemPosition);
                        if (oldItem == null || newItem == null) {
                            return false;
                        }
                        if (oldItem.getItemViewId() != newItem.getItemViewId() ||
                                oldItem.getClass() != newItem.getClass()) {
                            return false;
                        }
                        return AsyncListUpdateDiffer.this.mConfig.getDiffCallback().areItemsTheSame(oldItem, newItem);
                    }

                    @Override
                    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {

                        T oldItem = oldList.get(oldItemPosition);
                        T newItem = newList.get(newItemPosition);
                        if (oldItem != null && newItem != null && oldItem.getClass() == newItem.getClass()) {
                            return AsyncListUpdateDiffer.this.mConfig.getDiffCallback()
                                    .areContentsTheSame(oldItem, newItem);
                        } else {
                            return oldItem == null && newItem == null;
                        }
                    }

                    @Override
                    @Nullable
                    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
                        T oldItem = oldList.get(oldItemPosition);
                        T newItem = newList.get(newItemPosition);
                        if (oldItem != null && newItem != null && oldItem.getClass() == newItem.getClass()) {
                            return AsyncListUpdateDiffer.this.mConfig.getDiffCallback()
                                    .getChangePayload(oldItem, newItem);
                        } else {
                            return null;
                        }
                    }
                });
                mDiffHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // mMaxScheduledGeneration == runGeneration，说明runGeneration这次的操作就是最新的刷新操作
                        if (AsyncListUpdateDiffer.this.mMaxScheduledGeneration == runGeneration) {
                            AsyncListUpdateDiffer.this.latchList(newList, result, runGeneration);
                            Log.d(TAG, "latchList doDiff runGeneration :" + runGeneration + ";;size" +
                                    mGenerations.size());
                        } else {
                            // 这次操作已经失效，以最新的刷新操作为准
                            Log.d(TAG, "latchList doDiff else runGeneration :" + runGeneration + ";;size" +
                                    mGenerations.size());
                            mGenerations.remove(runGeneration);
                        }
                    }
                });
            }
        });
    }

    private void latchList(@NonNull final List<T> newList, @NonNull final DiffUtil.DiffResult diffResult,
                           final long runGeneration) {
        // 一个数据项，增加5毫秒的时间？ 用来协调多次submitList 线性执行
        long needDelay = mCanSyncTime - SystemClock.elapsedRealtime();
        if (needDelay <= 0) {

            syncOldList(newList);
            updateSyncTime(newList);
            updateCurrentList(new ArrayList<>(newList));
            diffResult.dispatchUpdatesTo(AsyncListUpdateDiffer.this.mUpdateCallback);
            mGenerations.remove(runGeneration);
            Log.d(TAG, "latchList needDelay <= 0 runGeneration :" + runGeneration + ";;size" + mGenerations.size());

        } else {

            mDiffHandler.postDelayed(new Runnable() {
                @Override
                public void run() {

                    if (AsyncListUpdateDiffer.this.mMaxScheduledGeneration == runGeneration) {

                        syncOldList(newList);
                        updateSyncTime(newList);
                        updateCurrentList(new ArrayList<>(newList));
                        diffResult.dispatchUpdatesTo(AsyncListUpdateDiffer.this.mUpdateCallback);

                    }
                    Log.d(TAG, "latchList else runGeneration :" + runGeneration + ";;size" + mGenerations.size());
                    mGenerations.remove(runGeneration);
                }
            }, needDelay);
        }

    }

    void updateOldListSize(final @NonNull Runnable listSizeRunnable, final List<T> oldDatas) {
        // 这里判断是不是在全量更新过程中，如果是的话，就没必要再更新了
        if (mGenerations.size() > 0) {
            return;
        }

        long currentTimeMillis = SystemClock.elapsedRealtime();

        if (currentTimeMillis >= mCanSyncTime) {

            listSizeRunnable.run();
            // 更新操作完成后，更新列表索引
            syncOldList(oldDatas);

        } else {
            final long runGeneration = AsyncListUpdateDiffer.this.mMaxScheduledGeneration;
            mDiffHandler.postDelayed(new Runnable() {
                @Override
                public void run() {

                    if (runGeneration == AsyncListUpdateDiffer.this.mMaxScheduledGeneration) {

                        listSizeRunnable.run();
                        syncOldList(oldDatas);
                    }
                }
            }, mCanSyncTime - currentTimeMillis);
        }
    }

    private void updateCurrentList(List<T> currentList) {
        this.mListChangedCallback.onListChanged(currentList);
    }

    private void syncOldList(@Nullable List<T> oldData) {
        this.mOldList = oldData;
    }

    private void updateSyncTime(@Nullable List<T> oldData) {
        // 一个数据项，增加5毫秒的时间？ 用来协调多次submitList 线性执行
        mCanSyncTime = SystemClock.elapsedRealtime() + (oldData != null ? oldData.size() * DELAY_STEP : 0);
    }


}
