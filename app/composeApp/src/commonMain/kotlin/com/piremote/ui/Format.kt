package com.piremote.ui

/**
 * Format a string resource template (`%1$s`, `%2$d`, …) outside composition.
 *
 * composeResources' `stringResource` only works in composable scope; coroutines
 * and callbacks pre-resolve the template with `stringResource(...)` and format
 * here with runtime arguments.
 */
fun formatTemplate(template: String, vararg args: Any?): String {
    var result = template
    args.forEachIndexed { index, arg ->
        val pos = index + 1
        result = result
            .replace("%$pos\$s", arg.toString())
            .replace("%$pos\$d", arg.toString())
    }
    return result
}
