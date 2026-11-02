package com.itsmebillah.appinternetcontrol

data class UserProfile(
    val id: String,
    val name: String,
    val blockedPackageNames: Set<String>,
    val allowedPackageNames: Set<String>,
) {
    init {
        require(name.isNotBlank())
        require(blockedPackageNames.intersect(allowedPackageNames).isEmpty())
    }
}
