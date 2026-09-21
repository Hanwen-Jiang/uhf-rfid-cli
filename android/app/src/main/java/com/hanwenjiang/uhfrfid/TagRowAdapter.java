package com.hanwenjiang.uhfrfid;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * 标签列表的一行。标签库页和"挑一颗来写"的弹窗共用。
 *
 * <p>行里的「写入」按钮在标签库页用来直接写；挑弹窗里也用同一个按钮当作"选这颗"。
 */
public class TagRowAdapter extends ArrayAdapter<CloudClient.Tag> {

    /** 点「写入」时回调。 */
    public interface OnWrite {
        void onWrite(CloudClient.Tag tag);
    }

    private final OnWrite onWrite;

    public TagRowAdapter(Context ctx, List<CloudClient.Tag> data, OnWrite onWrite) {
        super(ctx, R.layout.row_tag, data);
        this.onWrite = onWrite;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(getContext()).inflate(R.layout.row_tag, parent, false);
        }
        CloudClient.Tag t = getItem(position);
        if (t == null) return v;

        TextView name = v.findViewById(R.id.nameText);
        TextView sub = v.findViewById(R.id.subText);
        TextView epc = v.findViewById(R.id.epcText);
        View write = v.findViewById(R.id.writeBtn);

        boolean unnamed = t.name == null || t.name.isEmpty();
        name.setText(unnamed ? getContext().getString(R.string.cloud_unnamed) : t.name);
        name.setTextColor(unnamed ? 0xFF9E9E9E : 0xFF212121);

        StringBuilder sb = new StringBuilder();
        sb.append(getContext().getString(R.string.row_suffix, t.suffix));
        if ("anomaly".equals(t.status)) {
            sb.append("　·　").append(getContext().getString(R.string.row_anomaly));
        }
        if (t.seenCount > 0) {
            sb.append("　·　").append(getContext().getString(R.string.row_seen, t.seenCount));
        }
        if (t.writeCount > 0) {
            sb.append("　·　").append(getContext().getString(R.string.row_written, t.writeCount));
        }
        if (t.operator != null && !t.operator.isEmpty()) {
            sb.append("　·　").append(t.operator);
        }
        sub.setText(sb.toString());
        sub.setTextColor("anomaly".equals(t.status) ? 0xFFC62828 : 0xFF757575);

        epc.setText(t.epc);
        write.setOnClickListener(x -> onWrite.onWrite(t));
        return v;
    }
}
