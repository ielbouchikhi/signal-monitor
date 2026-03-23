package com.signalmonitor.ui.map

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem
import com.signalmonitor.data.db.MetricSample

data class MapSampleItem(val sample: MetricSample) : ClusterItem {
    override fun getPosition(): LatLng = LatLng(sample.latitude!!, sample.longitude!!)
    override fun getTitle(): String? = null
    override fun getSnippet(): String? = null
    override fun getZIndex(): Float? = null
}
