package com.silencedut.diffadapter;

import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MediatorLiveData;
import android.arch.lifecycle.Observer;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.util.DiffUtil;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.silencedut.diffadapter.data.BaseMutableData;
import com.silencedut.diffadapter.holder.BaseDiffViewHolder;
import com.silencedut.diffadapter.holder.NoDataDifferHolder;
import com.silencedut.diffadapter.utils.ListChangedCallback;
import com.silencedut.diffadapter.utils.UpdatePayloadFunction;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 大部分情况下是不需要再Adapter做过多的逻辑操作的，Adapter的目的就是用来组织Holder
 *
 * @author SilenceDut
 * @date 2018/9/6
 * <p>
 * When the async code is done, you should update the data, not the views. After updating the data, tell the adapter that the data changed. The RecyclerView gets note of this and re-renders your view.
 * When working with recycling views (ListView or RecyclerView), you cannot know what item a view is representing. In your case, that view gets recycled before the async work is done and is assigned to a different item of your data.
 * So never modify the view. Always modify the data and notify the adapter. bindView should be the place where you treat these cases.
 * <p>
 * 异步数据结果回来不应该直接改变view的状态，而是应该改变数据，然后通过adapter来改变View
 */
public class DiffAdapter extends RecyclerView.Adapter<BaseDiffViewHolder> {

    private static final String TAG = "DiffAdapter";
    private SparseArray<Class<? extends BaseDiffViewHolder>> typeHolders = new SparseArray<>();
    private List<BaseMutableData> mDatas;

    private LayoutInflater mInflater;
    private LifecycleOwner mLifecycleOwner;
    private AsyncListUpdateDiffer<BaseMutableData> mDifferHelper;
    private MediatorLiveData<Boolean> mUpdateMediatorLiveData = new MediatorLiveData<>();
    private long mCanUpdateTimeMill;
    private static final int UPDATE_DELAY_THRESHOLD = 100;
    Handler mDiffHandler = new Handler(Looper.getMainLooper());
    public Fragment attachedFragment;
    public Context mContext;

    @SuppressWarnings("unchecked")
    public DiffAdapter(FragmentActivity appCompatActivity) {
        doInit(appCompatActivity, appCompatActivity);
    }

    public DiffAdapter(Fragment attachedFragment) {
        doInit(attachedFragment.getActivity(), attachedFragment);
        this.attachedFragment = attachedFragment;
    }

    private void doInit(FragmentActivity appCompatActivity, LifecycleOwner lifecycleOwner) {
        this.mContext = appCompatActivity;
        this.mInflater = LayoutInflater.from(appCompatActivity);
        this.mLifecycleOwner = lifecycleOwner;
        this.mUpdateMediatorLiveData.observe(mLifecycleOwner, new Observer<Boolean>() {
            @Override
            public void onChanged(@Nullable Boolean o) {
            }
        });

        mDifferHelper = new AsyncListUpdateDiffer<>(this, new ListChangedCallback<BaseMutableData>() {
            @Override
            public void onListChanged(List<BaseMutableData> currentList) {
                mDatas = currentList;
            }
        }, new DiffUtil.ItemCallback<BaseMutableData>() {
            @Override
            public boolean areItemsTheSame(@NonNull BaseMutableData oldItem, @NonNull BaseMutableData newItem) {
                return oldItem.getItemViewId() == newItem.getItemViewId() &&
                        oldItem.uniqueItemFeature().equals(newItem.uniqueItemFeature());

            }

            @Override
            public boolean areContentsTheSame(@NonNull BaseMutableData oldItem, @NonNull BaseMutableData newItem) {

                return oldItem.areUISame(newItem);
            }

            @Override
            public Object getChangePayload(@NonNull BaseMutableData oldItem, @NonNull BaseMutableData newItem) {

                return oldItem.getPayloadKeys(newItem);
            }
        });
    }

    public void registerHolder(Class<? extends BaseDiffViewHolder> viewHolder, int itemViewType) {
        typeHolders.put(itemViewType, viewHolder);
    }

    public <T extends BaseMutableData> void registerHolder(Class<? extends BaseDiffViewHolder> viewHolder, T data) {
        if (data == null) {
            return;
        }
        typeHolders.put(data.getItemViewId(), viewHolder);

        addData(data);
    }

    public void registerHolder(Class<? extends BaseDiffViewHolder> viewHolder, List<? extends BaseMutableData> data) {
        if (data == null || data.size() == 0) {
            return;
        }
        typeHolders.put(data.get(0).getItemViewId(), viewHolder);
        setDatas(data);
    }

    private @Nullable Class findNeedUpdateDataType(Object updateFunction) {
        ParameterizedType parameterizedType = (ParameterizedType) updateFunction.getClass().getGenericInterfaces()[0];

        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        Type neededDataType;
        if (actualTypeArguments.length > 1) {
            neededDataType = actualTypeArguments[1];
        } else {
            return null;
        }
        Class clsType = null;
        if (neededDataType != null) {
            if (neededDataType instanceof Class) {
                clsType = (Class) neededDataType;
            } else if (neededDataType instanceof ParameterizedType) {
                Type type = ((ParameterizedType) neededDataType).getRawType();
                if (type instanceof Class) {
                    clsType = (Class) type;
                }
            }
        }
        return clsType;
    }

    /**
     * 更新单个item
     * @param elementData 提供更新数据的 LiveData,泛型是原始数据，而不是包装后的数据
     * @param updatePayloadFunction
     * @param <I> 泛型是未包装的原始数据
     * @param <R>
     */
    public <I, R extends BaseMutableData> void addUpdateMediator(LiveData<I> elementData,
                                                                 final UpdatePayloadFunction<I, R> updatePayloadFunction) {
        mUpdateMediatorLiveData.addSource(elementData, new Observer<I>() {
            @Override
            public void onChanged(@Nullable final I dataSource) {

                if (dataSource != null) {

                    Class clsType = findNeedUpdateDataType(updatePayloadFunction);

                    if (clsType == null) {
                        return;
                    }
                    // 从原始数据中获取匹配信息
                    Object matchFeature = updatePayloadFunction.providerMatchFeature(dataSource);
                    // 这里有可能匹配到多个item项
                    List<R> oldMatchedDatas = getMatchedData(matchFeature, clsType);

                    for (final R oldData : oldMatchedDatas) {
                        if (oldData != null) {
                            final R newData;
                            final Set<String> keys = oldData.getPayloadKeys();
                            newData = updatePayloadFunction.applyChange(dataSource, oldData, keys);
                            long current = SystemClock.elapsedRealtime();
                            // 这里使用了一个时间戳，保证同一时间只有一次更新操作可以更新
                            if (current > mCanUpdateTimeMill || getItemCount() < UPDATE_DELAY_THRESHOLD) {

                                updateData(newData, keys);
                                mCanUpdateTimeMill = current + AsyncListUpdateDiffer.DELAY_STEP;
                            } else {
                                long delay = mCanUpdateTimeMill - current;

                                mDiffHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateData(newData, keys);
                                    }
                                }, delay);
                                mCanUpdateTimeMill += AsyncListUpdateDiffer.DELAY_STEP;
                            }

                        }
                    }
                }
            }
        });
    }

    // 全量更新
    public void setDatas(List<? extends BaseMutableData> datas) {

        List<BaseMutableData> newList = new ArrayList<>(datas);
        mDifferHelper.submitList(newList);
    }

    public void clear() {

        mDifferHelper.submitList(null);

    }

    // 增
    public <T extends BaseMutableData> void addData(final T data) {
        if (data == null) {
            return;
        }
        mDifferHelper.updateOldListSize(new Runnable() {
            @Override
            public void run() {
                mDatas.add(data);
                notifyItemChanged(mDatas.size() - 1);

            }
        }, mDatas);
    }

    public <T extends BaseMutableData> void addDatas(final List<T> datas) {
        if (datas == null) {
            return;
        }
        mDifferHelper.updateOldListSize(new Runnable() {
            @Override
            public void run() {
                mDatas.addAll(datas);
                notifyItemChanged(mDatas.size() - datas.size());
            }
        }, mDatas);

    }

    public void deleteData(final Object uniqueItemFeature) {
        if (uniqueItemFeature == null) {
            return;
        }

        mDifferHelper.updateOldListSize(new Runnable() {
            @Override
            public void run() {
                Iterator<BaseMutableData> iterator = mDatas.iterator();

                int position = -1;
                while (iterator.hasNext()) {
                    position++;

                    if (uniqueItemFeature.equals(iterator.next().uniqueItemFeature())) {
                        iterator.remove();
                        break;
                    }
                }
                notifyItemRemoved(position);
            }
        }, mDatas);
    }

    // 删
    public void deleteData(final BaseMutableData data) {
        if (data == null) {
            return;
        }

        mDifferHelper.updateOldListSize(new Runnable() {
            @Override
            public void run() {
                Iterator<BaseMutableData> iterator = mDatas.iterator();

                int position = -1;
                while (iterator.hasNext()) {
                    position++;

                    if (data.uniqueItemFeature().equals(iterator.next().uniqueItemFeature())) {
                        iterator.remove();
                        break;
                    }
                }
                notifyItemRemoved(position);
            }
        }, mDatas);
    }

    public void deleteData(final int startPosition, final int size) {
        if (startPosition + size >= mDatas.size()) {
            return;
        }

        mDifferHelper.updateOldListSize(new Runnable() {
            @Override
            public void run() {
                Iterator<BaseMutableData> iterator = mDatas.iterator();
                int deleteSize = 0;
                int startIndex = 0;
                while (startIndex < startPosition && iterator.hasNext()) {
                    startIndex++;
                    iterator.next();
                }
                while (iterator.hasNext() && deleteSize < size) {
                    iterator.next();
                    iterator.remove();
                    deleteSize++;
                }

                notifyItemRangeRemoved(startPosition, deleteSize);
            }
        }, mDatas);

    }

    public void insertData(final int startPosition, final List<? extends BaseMutableData> datas) {
        if (datas == null || datas.isEmpty()) {
            return;
        }

        mDifferHelper.updateOldListSize(new Runnable() {
            @Override
            public void run() {
                if (startPosition > datas.size()) {
                    mDatas.addAll(datas);
                } else {
                    mDatas.addAll(startPosition, datas);
                }

                notifyItemRangeInserted(startPosition, datas.size());
            }
        }, mDatas);

    }

    private void updateData(BaseMutableData newData, @NonNull Set<String> payloadKeys) {
        if (newData == null) {
            return;
        }
        Iterator<BaseMutableData> iterator = mDatas.iterator();
        int foundIndex = -1;
        // 遍历所有数据，进行匹配更新
        while (iterator.hasNext()) {
            BaseMutableData data = iterator.next();
            foundIndex++;
            // 如果item类型一致，特征一致，更新数据
            if (data.getItemViewId() == newData.getItemViewId()
                    && newData.uniqueItemFeature().equals(data.uniqueItemFeature())) {

                mDatas.set(foundIndex, newData);

                if (mDatas.size() > foundIndex) {
                    // 如果支持Payload更新，就是用性能更好的Payload更新
                    Set<String> dataPayloadKeys = data.getPayloadKeys(newData);

                    payloadKeys.addAll(dataPayloadKeys);
                    if (payloadKeys.isEmpty()) {
                        notifyItemChanged(foundIndex);
                    } else {
                        Log.d(TAG, "notifyItemChanged :" + foundIndex + ",payloadKeys:" + payloadKeys);
                        notifyItemChanged(foundIndex, payloadKeys);
                    }
                }
            }
        }
    }

    public void updateData(BaseMutableData newData) {
        updateData(newData, newData.getPayloadKeys());
    }

    @NonNull
    @Override
    public BaseDiffViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = mInflater.inflate(viewType, parent, false);
        BaseDiffViewHolder viewHolder = new NoDataDifferHolder(itemView, this);
        try {
            Class<?> cls = typeHolders.get(viewType);
            Constructor holderConstructor = cls.getDeclaredConstructor(View.class, DiffAdapter.class);
            holderConstructor.setAccessible(true);
            viewHolder = (BaseDiffViewHolder) holderConstructor.newInstance(itemView, this);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "Create  error,is it a inner class? can't create no static inner ViewHolder ");
        } catch (Exception e) {
            Log.e(TAG, e.getCause() + "");
        }
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull BaseDiffViewHolder baseDiffViewHolder, int position) {

        try {
            baseDiffViewHolder.update(mDatas.get(position), position);
        } catch (Exception e) {
            Log.e(TAG, "onBindViewHolder updatePartWithPayload error", e);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull BaseDiffViewHolder holder, int position, @NonNull List<Object> payloads) {
        Log.d(TAG, "onBindViewHolder updatePartWithPayload position" + position + ",,payloads" + payloads);
        if (mDatas.size() == 0 || mDatas.get(position) == null) {
            return;
        }

        if (getItemViewType(position) != holder.getItemViewId()) {
            return;
        }
        Set<String> payloadKeys = null;
        if (payloads.isEmpty()) {
            this.onBindViewHolder(holder, position);
        } else {
            try {
                for (Object payload : payloads) {
                    if (payload instanceof HashSet) {
                        if (payloadKeys == null) {
                            payloadKeys = (HashSet) payload;
                        } else {
                            payloadKeys.addAll((HashSet) payload);
                        }
                    }
                }
                if (payloadKeys ==null ||  payloadKeys.isEmpty()) {
                    this.onBindViewHolder(holder, position);
                } else {
                    holder.updatePartWithPayload(mDatas.get(position), payloadKeys, position);
                }

            } catch (Exception e) {
                Log.e(TAG, "onBindViewHolder updatePartWithPayload payload error", e);
            }
        }
        if (payloadKeys != null) {
            payloadKeys.clear();
        }
        mDatas.get(position).getPayloadKeys().clear();
    }

    public <T extends BaseMutableData> List<T> getMatchedData(Object matchChangeFeature, Class cls) {
        List<T> matchedMutableData = new ArrayList<>();
        for (BaseMutableData baseMutableData : mDatas) {
            if (baseMutableData != null && baseMutableData.matchChangeFeatures().contains(matchChangeFeature) &&
                    cls.isInstance(baseMutableData)) {
                matchedMutableData.add((T) baseMutableData);
            }
        }
        return matchedMutableData;

    }

    public <T extends BaseMutableData> List<T> getData(Class<T> tClass) {
        List<T> classLists = new ArrayList<>();
        for (BaseMutableData baseMutableData : mDatas) {
            if (tClass.isInstance(baseMutableData)) {
                classLists.add((T) baseMutableData);
            }
        }
        return classLists;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        Log.d(TAG, " onDetachedFromRecyclerView ");
        mDiffHandler.removeCallbacksAndMessages(null);
    }


    /**
     * 当前显示在列表中的数据，和{@link #setDatas(List)}里的数据大小可能不一样，由于DiffUtil可能还在计算的问题
     * 通过提供的接口来改变数据
     */
    public List<BaseMutableData> getDatas() {
        return mDatas;
    }

    @Override
    public int getItemCount() {
        return mDatas.size();
    }

    @Override
    public int getItemViewType(int position) {

        if (mDatas.get(position) != null) {
            return mDatas.get(position).getItemViewId();
        }

        return 0;
    }

}
