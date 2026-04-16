package com.example.appcore;

import com.example.appcore.IAnalysisCb;

interface IAnalysisSvc {
    void processDataAsync(String rawData, IAnalysisCb cb);
}