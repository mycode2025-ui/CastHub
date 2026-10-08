package com.casthub.dlna.upnp

class UpnpActionException(val code: Int, val description: String) : java.io.IOException(description)
