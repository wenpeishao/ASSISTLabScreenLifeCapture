package com.screenomics;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {
    
    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:
                return new ScreenLifeFragment();
            case 1:
                return new MindPulseFragment();
            default:
                return new ScreenLifeFragment();
        }
    }

    @Override
    public int getItemCount() {
        // Only show MindPulse tab when enabled (after IRB approval)
        return BuildConfig.MINDPULSE_ENABLED ? 2 : 1;
    }

    public String getTabTitle(int position) {
        switch (position) {
            case 0:
                return "MindPulse";
            case 1:
                // MindPulse tab - only visible when MINDPULSE_ENABLED = true
                return BuildConfig.MINDPULSE_ENABLED ? "MindPulse" : "MindPulse";
            default:
                return "MindPulse";
        }
    }
}
