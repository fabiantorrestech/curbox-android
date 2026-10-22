package neth.iecal.curbox.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

class FocusSafeNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    override fun findFocus(): View? {
        val focused = super.findFocus() ?: return null
        var ancestor: View? = focused
        while (ancestor != null) {
            if (ancestor === this) return focused
            ancestor = ancestor.parent as? View
        }
        // Removed RecyclerView children can remain cached as focused during a sheet resize.
        // NestedScrollView must not calculate scroll coordinates for those detached views.
        return null
    }
}
