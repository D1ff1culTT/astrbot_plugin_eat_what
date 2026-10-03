package com.eatwhat.app;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * 不过滤的 ArrayAdapter：联想词已在服务端过滤，
 * 这里绕开 AutoCompleteTextView 自带的本地前缀过滤，直接展示全部候选。
 */
public class SuggestAdapter extends ArrayAdapter<String> {

    private final ArrayList<String> data;

    public SuggestAdapter(Context c) {
        this(c, new ArrayList<>());
    }

    private SuggestAdapter(Context c, ArrayList<String> shared) {
        super(c, android.R.layout.simple_list_item_1, shared);
        data = shared;
    }

    public void replace(List<String> items) {
        data.clear();
        data.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults r = new FilterResults();
                r.values = data;
                r.count = data.size();
                return r;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }
        };
    }
}
