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
        // Show both tabs: ScreenLife (screenshots) and Video
        return 2;
    }

    public String getTabTitle(int position) {
        switch (position) {
            case 0:
                return "ScreenLife";
            case 1:
                return "Video";
            default:
                return "ScreenLife";
        }
    }
}
