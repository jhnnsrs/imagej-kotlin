package com.mycompany.arkitekt

import com.apollographql.apollo.api.Optional
import com.mycompany.rekuest.graphql.type.ActionKind
import com.mycompany.rekuest.graphql.type.ArgPortInput
import com.mycompany.rekuest.graphql.type.DefinitionInput
import com.mycompany.rekuest.graphql.type.PortKind
import com.mycompany.rekuest.graphql.type.ReturnPortInput

// The actions this plugin advertises to rekuest: a typed port definition per handler.
//
// Pure data, lifted out of `alogin` so the auth/composition logic there stays readable. To add
// an action, write the handler in Arkitekt.kt and register it here — it is auto-advertised on
// the next login.
fun buildFunctionRegistry(arkitekt: Arkitekt): FunctionRegistry {
    val registry = FunctionRegistry()

        registry.register_function(
            "frage",
            DefinitionInput(
                key = "frage",
                version = "0.1.0",
                name = "Upload Image",
                description =
                    Optional.present(
                        "Upload the currently active image in the viewer."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "name",
                                kind = PortKind.STRING,
                                description =
                                    Optional.present(
                                        "How would you like to name the image?"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The returned image"
                                    )
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::runX
        )

        registry.register_function(
            "show_image",
            DefinitionInput(
                key = "show_image",
                version = "0.1.0",
                name = "Show Image",
                description =
                    Optional.present(
                        "Show the currently active Image in the viewer."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The image to show"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The image that was shown image"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::loadImage
        )

        registry.register_function(
            "run_image_to_image_macro",
            DefinitionInput(
                key = "run_image_to_image_macro",
                version = "0.1.0",
                name = "Run Image-To-Image Macro",
                description =
                    Optional.present(
                        "Run an arbitrary ImageJ macro over an image and return the result as a new image."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The image to run the macro on"
                                    ),
                                nullable = Optional.present(false)
                            ),
                            ArgPortInput(
                                key = "macro",
                                kind = PortKind.STRING,
                                description =
                                    Optional.present(
                                        "The ImageJ macro to run. It operates on the image as the current image, e.g. run(\"Gaussian Blur...\", \"sigma=2\")."
                                    ),
                                nullable = Optional.present(false)
                            ),
                            ArgPortInput(
                                key = "name",
                                kind = PortKind.STRING,
                                description =
                                    Optional.present(
                                        "How would you like to name the resulting image?"
                                    ),
                                nullable = Optional.present(true)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The resulting image after running the macro"
                                    )
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            arkitekt::runImageToImageMacro
        )

    return registry
}
