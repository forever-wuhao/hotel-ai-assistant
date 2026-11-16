// The optional legacy admin map uses the owner's JS SDK key and security proxy.
// Public hotel consultation does not need the browser SDK.
export async function loadAMap(): Promise<void> {
  const key = import.meta.env.VITE_AMAP_JS_KEY
  const serviceHost = import.meta.env.VITE_AMAP_SECURITY_PROXY
  if (!key || !serviceHost) return
  Object.assign(window, { _AMapSecurityConfig: { serviceHost } })
  await new Promise<void>((resolve) => {
    const script = document.createElement('script')
    const timeout = setTimeout(resolve, 8000)
    const finish = () => { clearTimeout(timeout); resolve() }
    script.onload = finish
    script.onerror = finish
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.PlaceSearch,AMap.Geocoder`
    document.head.appendChild(script)
  })
}
