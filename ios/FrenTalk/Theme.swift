import SwiftUI

/// Mirrors the palette in public/style.css so the native shell and the web
/// view read as one app.
enum Theme {
    static let ink    = Color(red: 0.078, green: 0.067, blue: 0.059)
    static let panel  = Color(red: 0.129, green: 0.110, blue: 0.094)
    static let edge   = Color(red: 0.239, green: 0.200, blue: 0.169)
    static let cream  = Color(red: 0.925, green: 0.890, blue: 0.831)
    static let dim    = Color(red: 0.549, green: 0.498, blue: 0.439)
    static let amber  = Color(red: 0.910, green: 0.639, blue: 0.239)
    static let live   = Color(red: 0.847, green: 0.271, blue: 0.184)
}
