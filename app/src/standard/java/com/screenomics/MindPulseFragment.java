package com.screenomics;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Placeholder fragment shown in standard builds where the MindPulse
 * experience is disabled. This keeps the ViewPager logic simple while
 * ensuring we do not ship the heavy camera dependencies that would
 * otherwise violate the 16 KB native alignment requirement.
 */
public class MindPulseFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mindpulse, container, false);
    }
}
