package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;
import org.junit.Test;

/** Verify shipped metadata, not a fictional selector or a device rendering claim. */
public class ShippedHookProfileTest {
    private static JSONObject profile(int version) throws Exception {
        String json = Files.readString(Path.of("src/main/assets/hook-config/" + version + ".json"));
        HookConfiguration.parse(json, version);
        return new JSONObject(json);
    }

    @Test
    public void inactiveFeaturePathsAndDiagnosticFieldsAreNotShipped() throws Exception {
        for (int version : new int[]{340013, 352017}) {
            JSONObject root = profile(version);
            assertEquals(1, root.getInt("schemaVersion"));
            JSONObject hooks = root.getJSONObject("hooks");
            JSONObject embed = hooks.getJSONObject("embed.bind");
            assertTrue(embed.getBoolean("enabled"));
            assertFalse(embed.has("reason"));
            JSONObject members = root.optJSONObject("members");
            for (String key : new String[]{"embedHolderViewField", "embedMessageIdGetter",
                    "embedGetter", "embedDescriptionGetter", "embedTitleGetter",
                    "embedFieldsGetter", "embedFieldNameGetter", "embedFieldValueGetter"}) {
                assertTrue(members == null || !members.has(key));
            }
            assertEquals(version == 352017, hooks.has("poll.bind"));
            assertFalse(hooks.has("embed.postPreviewBind"));
            assertFalse(hooks.has("embed.postPreviewRender"));
            assertFalse(root.has("constructorFallbackTypes"));
            JSONObject recycler = root.getJSONObject("fields").getJSONObject("RecyclerView");
            for (String key : new String[]{"stateP", "stateQ", "stateR", "stateS", "stateT", "stateU"}) {
                assertFalse(recycler.has(key));
            }
        }
    }

    @Test
    public void currentProfileHasOneExplicitIdentityAndConstructorRoute() throws Exception {
        JSONObject root = profile(352017);
        assertEquals("argument", root.getJSONObject("hooks").getJSONObject("message.render").getString("identitySource"));
        assertEquals("constructorProxy", root.getJSONObject("iconClick").getString("protocol"));
        assertEquals("c", root.getJSONObject("fields").getJSONObject("UnicodeEmojiContentNode").getString("getEmojiAnchor"));
        JSONObject constructors = root.getJSONObject("nodeConstructors");
        assertEquals(12, constructors.length());
        JSONObject heading = constructors.getJSONObject("HeadingContentNode");
        assertEquals("java.util.List", heading.getJSONArray("parameterTypes").getString(0));
        assertEquals("children", heading.getJSONArray("arguments").getJSONObject(0).getString("kind"));
        assertEquals(3, heading.getJSONArray("arguments").getJSONObject(1).getInt("value"));
        assertEquals("a", heading.getJSONArray("arguments").getJSONObject(2).getString("name"));
        assertFalse(root.getJSONObject("hooks").getJSONObject("message.bind").has("replyPreviewUsernameField"));
    }

    @Test
    public void currentEmbedUsesExactSmaliJavaSignatureAndStructuredFields() throws Exception {
        JSONObject root = profile(352017);
        JSONObject embed = root.getJSONObject("hooks").getJSONObject("embed.bind");
        JSONObject target = embed.getJSONObject("target");
        assertEquals("q9.q", target.getString("class"));
        assertEquals("b", target.getString("name"));
        assertFalse(target.getBoolean("static"));
        assertEquals("void", target.getString("returnType"));
        assertEquals(34, target.getInt("parameterCount"));
        assertFalse(target.has("namePrefix"));
        assertFalse(target.has("minParameterCount"));
        assertFalse(target.has("selection"));
        // J and D each occupy one Java argument, not two smali register slots.
        String[] expectedTypes = {
                "m9.f0", "n7.c", "long", "java.lang.String", "int", "int", "int",
                "boolean", "boolean", "boolean", "boolean", "boolean", "boolean",
                "ea.n", "ea.n", "cb.i0", "ag.c", "android.view.View$OnLongClickListener",
                "a50.r", "af.m", "af.m", "k9.g", "af.m", "ea.b", "ea.c",
                "af.m", "af.m", "af.m", "j8.c", "double",
                "boolean", "boolean", "boolean", "boolean"
        };
        JSONObject parameterTypes = target.getJSONObject("parameterTypes");
        assertEquals(34, expectedTypes.length);
        assertEquals(expectedTypes.length, parameterTypes.length());
        for (int i = 0; i < expectedTypes.length; i++) {
            assertEquals("Java parameter " + i, expectedTypes[i],
                    parameterTypes.getString(Integer.toString(i)));
        }
        assertEquals("arguments", embed.getString("inputSource"));
        assertEquals(1, embed.getInt("modelArgument"));
        assertEquals(3, embed.getInt("messageIdArgument"));
        assertFalse(embed.has("accessoryArgument"));
        JSONObject host = embed.getJSONObject("host");
        assertEquals("this", host.getString("source"));
        assertEquals(1, host.length());
        assertEquals(8, root.getJSONArray("requiredHooks").length());
        assertEquals("embed.bind", root.getJSONArray("requiredHooks").getString(3));
        JSONObject types = root.getJSONObject("types");
        assertEquals("n7.c", types.getString("Embed"));
        assertEquals("n7.o", types.getString("EmbedField"));
        JSONObject fields = root.getJSONObject("fields");
        JSONObject embedFields = fields.getJSONObject("Embed");
        assertEquals(3, embedFields.length());
        assertEquals("j", embedFields.getString("getDescription"));
        assertEquals("f", embedFields.getString("getTitle"));
        assertEquals("g", embedFields.getString("getFields"));
        JSONObject itemFields = fields.getJSONObject("EmbedField");
        assertEquals(2, itemFields.length());
        assertEquals("c", itemFields.getString("getName"));
        assertEquals("d", itemFields.getString("getValue"));
    }

    @Test
    public void pollUsesExactNativeScopesAndReceiversOnly() throws Exception {
        JSONObject root = profile(352017);
        String[] ids = {"poll.bind", "poll.answerBind", "poll.textSet", "poll.accessibilitySet"};
        String[] owners = {"ea.r0", "ba.h", "android.widget.TextView", "android.view.View"};
        String[] methods = {"v", "f", "setText", "setContentDescription"};
        String[][] parameters = {{"m9.g0", "ea.n", "ea.n", "k9.l"}, {"k3.k1", "int"},
                {"java.lang.CharSequence"}, {"java.lang.CharSequence"}};
        for (int i = 0; i < ids.length; i++) {
            JSONObject hook = root.getJSONObject("hooks").getJSONObject(ids[i]);
            JSONObject target = hook.getJSONObject("target");
            assertTrue(hook.getBoolean("enabled"));
            assertEquals(ids[i], root.getJSONArray("requiredHooks").getString(i + 4));
            assertEquals(owners[i], target.getString("class"));
            assertEquals(methods[i], target.getString("name"));
            assertFalse(target.getBoolean("static"));
            assertEquals("void", target.getString("returnType"));
            assertEquals(parameters[i].length, target.getInt("parameterCount"));
            assertEquals(parameters[i].length, target.getJSONObject("parameterTypes").length());
            for (int j = 0; j < parameters[i].length; j++) {
                assertEquals(parameters[i][j], target.getJSONObject("parameterTypes")
                        .getString(Integer.toString(j)));
            }
        }
        JSONObject question = root.getJSONObject("hooks").getJSONObject("poll.bind");
        assertEquals(0, question.getInt("accessoryArgument"));
        assertEquals("[\"h\"]", question.getJSONArray("optionsPath").toString());
        assertEquals("[\"g\",\"b\",\"a\"]", question.getJSONArray("textPath").toString());
        assertEquals("[\"e\"]", question.getJSONArray("messageIdPath").toString());
        assertEquals("[\"u\",\"P\",\"w\"]", question.getJSONArray("textViewPath").toString());
        JSONObject answer = root.getJSONObject("hooks").getJSONObject("poll.answerBind");
        assertEquals(0, answer.getInt("holderArgument"));
        assertEquals(1, answer.getInt("positionArgument"));
        assertEquals("[\"d\"]", answer.getJSONArray("itemsPath").toString());
        assertEquals("[\"c\",\"a\"]", answer.getJSONArray("answerIdPath").toString());
        assertEquals("[\"c\",\"b\",\"a\"]", answer.getJSONArray("textPath").toString());
        assertEquals("q8.f0", answer.getString("textBindingClass"));
        assertEquals("q8.d0", answer.getString("imageBindingClass"));
        assertEquals("[\"v\"]", answer.getJSONArray("textViewPath").toString());
        assertEquals("[\"i\"]", answer.getJSONArray("imageViewPath").toString());
        for (String id : ids) assertFalse(profile(340013).getJSONObject("hooks").has(id));
    }

    @Test
    public void malformedPollProfilesRejectBeforeInstallation() throws Exception {
        for (int caseIndex = 0; caseIndex < 4; caseIndex++) {
            JSONObject root = profile(352017);
            JSONObject hooks = root.getJSONObject("hooks");
            switch (caseIndex) {
                case 0: hooks.remove("poll.textSet"); break;
                case 1: hooks.getJSONObject("poll.bind").put("accessoryArgument", 4); break;
                case 2: hooks.getJSONObject("poll.answerBind").put("textPath", new org.json.JSONArray()); break;
                case 3: hooks.getJSONObject("poll.accessibilitySet").put("enabled", false); break;
            }
            assertThrows(org.json.JSONException.class,
                    () -> HookConfiguration.parse(root.toString(), 352017));
        }
    }

    @Test
    public void malformedEmbedInputsAreRejectedBeforeInstallation() throws Exception {
        for (int caseIndex = 0; caseIndex < 5; caseIndex++) {
            JSONObject root = profile(352017);
            JSONObject embed = root.getJSONObject("hooks").getJSONObject("embed.bind");
            switch (caseIndex) {
                case 0: embed.put("accessoryArgument", 0); break;
                case 1: embed.put("messageIdArgument", 34); break;
                case 2: embed.put("inputSource", "guess"); break;
                case 3: embed.getJSONObject("host").put("source", "ancestor"); break;
                case 4: embed.getJSONObject("target").put("static", true); break;
            }
            assertThrows(org.json.JSONException.class,
                    () -> HookConfiguration.parse(root.toString(), 352017));
        }
    }

    @Test
    public void olderProfileUsesNativeMessageIdentityReplyAndRichEmbedMetadata() throws Exception {
        JSONObject root = profile(340013);
        JSONObject hooks = root.getJSONObject("hooks");
        assertEquals(4, hooks.length());
        JSONObject bind = hooks.getJSONObject("message.bind");
        assertEquals(0, bind.getInt("modelArgument"));
        assertEquals("getId", bind.getString("messageIdGetter"));
        assertEquals("getContent", bind.getString("contentGetter"));
        assertEquals("getEditedTimestamp", bind.getString("editedTimestampGetter"));
        assertFalse(bind.has("contentViewField"));
        JSONObject reference = bind.getJSONObject("referencedMessage");
        assertEquals(7, reference.length());
        assertEquals("getReferencedMessage", reference.getString("accessor"));
        assertEquals("com.discord.chat.bridge.referencedmessage.LoadedReferencedMessage",
                reference.getString("loadedClass"));
        assertEquals("message", reference.getString("messageField"));
        assertEquals("getId", reference.getString("messageIdGetter"));
        assertEquals("systemContent", reference.getString("contentField"));
        assertEquals("getContent", reference.getString("messageContentGetter"));
        assertEquals("getEditedTimestamp", reference.getString("editedTimestampGetter"));
        assertEquals("replyPreview", bind.getString("replyPreviewField"));
        assertEquals("binding", bind.getString("replyPreviewBindingField"));
        assertEquals("replyText", bind.getString("replyPreviewTextField"));
        assertFalse(root.has("nodeConstructors"));
        JSONObject render = hooks.getJSONObject("message.render");
        assertEquals("argument", render.getString("identitySource"));
        assertEquals(1, render.getInt("contentArgument"));
        assertEquals(2, render.getInt("messageIdArgument"));
        JSONObject embed = hooks.getJSONObject("embed.bind");
        assertTrue(embed.getBoolean("enabled"));
        JSONObject target = embed.getJSONObject("target");
        assertEquals("com.discord.chat.presentation.message.viewholder.EmbedViewHolder",
                target.getString("class"));
        assertEquals("bind", target.getString("name"));
        assertFalse(target.getBoolean("static"));
        assertEquals(9, target.getInt("parameterCount"));
        assertEquals(9, target.getJSONObject("parameterTypes").length());
        assertEquals("com.discord.chat.presentation.message.messagepart.EmbedMessageAccessory",
                target.getJSONObject("parameterTypes").getString("1"));
        assertEquals("void", target.getString("returnType"));
        assertEquals(6, target.length());
        assertEquals("accessory", embed.getString("inputSource"));
        assertEquals(1, embed.getInt("accessoryArgument"));
        assertFalse(embed.has("modelArgument"));
        assertFalse(embed.has("messageIdArgument"));
        JSONObject host = embed.getJSONObject("host");
        assertEquals("field", host.getString("source"));
        assertEquals("itemView", host.getString("field"));
        assertEquals(2, host.length());
        assertEquals("[\"message.bind\",\"message.render\",\"message.textConversion\",\"embed.bind\"]",
                root.getJSONArray("requiredHooks").toString());
        JSONObject members = root.getJSONObject("members");
        assertFalse(members.has("getMessageId"));
        for (String getter : new String[]{"getEmbed", "getDescription", "getTitle",
                "getFields", "getName", "getValue"}) {
            assertEquals(getter, members.getString(getter));
        }
        JSONObject types = root.getJSONObject("types");
        JSONObject accessors = root.getJSONObject("accessors");
        assertFalse(types.has("MessageContentAccessory"));
        assertFalse(accessors.has("MessageContentAccessory"));
        assertEquals(1, root.getJSONObject("fields").getJSONObject("MessageContentViewHolder").length());
        assertEquals("com.discord.chat.bridge.Message", types.getString("Message"));
        assertEquals("getId-3Eiw7ao", accessors.getJSONObject("Message").getString("getId"));
        assertEquals("com.discord.chat.presentation.message.messagepart.EmbedMessageAccessory",
                types.getString("EmbedMessageAccessory"));
        assertEquals("getMessageId-3Eiw7ao",
                accessors.getJSONObject("EmbedMessageAccessory").getString("getMessageId"));
        assertEquals("com.discord.chat.bridge.embed.Embed", types.getString("Embed"));
        assertEquals("com.discord.chat.bridge.embed.EmbedField", types.getString("EmbedField"));
        assertEquals("com.discord.chat.presentation.message.viewholder.MessageContentViewHolder",
                types.getString("MessageContentViewHolder"));
    }

    @Test
    public void olderProfileRetainsCompleteConfiguredMessageSelectors() throws Exception {
        JSONObject hooks = profile(340013).getJSONObject("hooks");
        JSONObject bind = hooks.getJSONObject("message.bind").getJSONObject("target");
        assertEquals("com.discord.chat.presentation.message.MessageView", bind.getString("class"));
        assertEquals("setMessage", bind.getString("name"));
        assertFalse(bind.getBoolean("static"));
        String[] bindTypes = {
                "com.discord.chat.bridge.Message",
                "com.discord.chat.presentation.root.MessageContext",
                "com.discord.chat.presentation.events.ChatEventHandler",
                "com.discord.chat.presentation.message.view.botuikit.ComponentProvider",
                "kotlin.jvm.functions.Function0", "boolean", "boolean"
        };
        assertEquals(7, bind.getInt("parameterCount"));
        assertEquals(bindTypes.length, bind.getJSONObject("parameterTypes").length());
        for (int i = 0; i < bindTypes.length; i++) {
            assertEquals("native setMessage parameter " + i, bindTypes[i],
                    bind.getJSONObject("parameterTypes").getString(Integer.toString(i)));
        }
        assertEquals("void", bind.getString("returnType"));
        assertEquals(6, bind.length());

        JSONObject render = hooks.getJSONObject("message.render").getJSONObject("target");
        assertEquals("com.discord.chat.presentation.message.view.MessageContentView",
                render.getString("class"));
        assertEquals("setMessageContent-hYrISQc", render.getString("name"));
        assertFalse(render.getBoolean("static"));
        assertEquals(34, render.getInt("parameterCount"));
        assertFalse(render.has("minParameterCount"));
        assertFalse(render.has("selection"));
        assertEquals(34, render.getJSONObject("parameterTypes").length());
        assertEquals("com.discord.chat.presentation.message.messagepart.MessageMargins",
                render.getJSONObject("parameterTypes").getString("0"));
        assertEquals("com.discord.chat.bridge.structurabletext.StructurableText",
                render.getJSONObject("parameterTypes").getString("1"));
        assertEquals("java.lang.String", render.getJSONObject("parameterTypes").getString("2"));
        assertEquals("void", render.getString("returnType"));
        assertEquals(6, render.length());

        JSONObject conversion = hooks.getJSONObject("message.textConversion");
        JSONObject target = conversion.getJSONObject("target");
        assertEquals("com.discord.chat.presentation.textutils.TextUtilsKt", target.getString("class"));
        assertEquals("toSpannable", target.getString("name"));
        assertTrue(target.getBoolean("static"));
        assertEquals(29, target.getInt("parameterCount"));
        assertEquals(29, target.getJSONObject("parameterTypes").length());
        assertEquals("com.discord.chat.bridge.structurabletext.StructurableText",
                target.getJSONObject("parameterTypes").getString("0"));
        assertEquals("com.facebook.drawee.span.DraweeSpanStringBuilder", target.getString("returnType"));
        assertEquals(6, target.length());
        assertEquals(0, conversion.getInt("contentArgument"));
        assertEquals(1, conversion.getInt("contextArgument"));
        assertEquals(2, conversion.getInt("messageIdArgument"));
        assertEquals("java.lang.String", target.getJSONObject("parameterTypes").getString("2"));
    }

    @Test
    public void olderProfileRefreshUsesVerifiedHolderCacheAndObservableMembers() throws Exception {
        JSONObject root = profile(340013);
        JSONObject fields = root.getJSONObject("fields");
        assertEquals("d", fields.getJSONObject("RecyclerViewLayoutParams").getString("viewHolder"));
        assertEquals("lastMessageContent",
                fields.getJSONObject("MessageContentViewHolder").getString("bindCache"));
        assertEquals("mObservable", fields.getJSONObject("RecyclerViewAdapter").getString("observable"));
        JSONObject holder = fields.getJSONObject("RecyclerViewViewHolder");
        assertEquals("itemView", holder.getString("itemView"));
        assertEquals("mBindingAdapter", holder.getString("bindingAdapter"));
        assertEquals("mOwnerRecyclerView", holder.getString("recyclerView"));
        JSONObject accessors = root.getJSONObject("accessors");
        assertEquals("d", accessors.getJSONObject("RecyclerViewAdapterObservable")
                .getString("notifyItemChanged"));
        assertEquals("getBindingAdapterPosition", accessors.getJSONObject("RecyclerViewViewHolder")
                .getString("getBindingAdapterPosition"));
        assertEquals("getAbsoluteAdapterPosition", accessors.getJSONObject("RecyclerViewViewHolder")
                .getString("getAbsoluteAdapterPosition"));
    }

    @Test
    public void olderProfileClickUsesExactNativeSyntheticConstructorAndDefaultMask() throws Exception {
        JSONObject click = profile(340013).getJSONObject("iconClick");
        assertTrue(click.getBoolean("enabled"));
        assertFalse(click.has("reason"));
        assertEquals("constructorProxy", click.getString("protocol"));
        assertEquals("com.discord.span.utilities.spannable.ClickableSpan", click.getString("spanClass"));
        assertEquals("android.text.style.ClickableSpan", click.getString("requiredSuperclass"));
        assertEquals(1, click.getJSONArray("interfaces").length());
        assertEquals("com.discord.react_gesture_handler.nested_touch.NestedClickableSpan",
                click.getJSONArray("interfaces").getString(0));
        JSONObject constructor = click.getJSONObject("constructor");
        assertEquals(2, constructor.length());
        String[] expectedTypes = {
                "com.discord.react_gesture_handler.nested_touch.NestedClickableSpan$TouchPriority",
                "java.lang.Integer", "java.lang.Integer", "java.lang.Integer", "float",
                "java.lang.Boolean", "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function1",
                "int", "kotlin.jvm.internal.DefaultConstructorMarker"
        };
        org.json.JSONArray parameterTypes = constructor.getJSONArray("parameterTypes");
        org.json.JSONArray arguments = constructor.getJSONArray("arguments");
        assertEquals(10, parameterTypes.length());
        assertEquals(10, arguments.length());
        for (int i = 0; i < expectedTypes.length; i++) {
            assertEquals("native constructor parameter " + i, expectedTypes[i], parameterTypes.getString(i));
        }
        for (int i : new int[]{0, 1, 2, 3, 5, 6, 9}) {
            assertEquals("null", arguments.getJSONObject(i).getString("kind"));
            assertEquals(1, arguments.getJSONObject(i).length());
        }
        JSONObject floatArgument = arguments.getJSONObject(4);
        assertEquals("float", floatArgument.getString("kind"));
        assertEquals(0.0, floatArgument.getDouble("value"), 0.0);
        assertEquals(2, floatArgument.length());
        JSONObject callback = arguments.getJSONObject(7);
        assertEquals("callback", callback.getString("kind"));
        assertEquals("kotlin.jvm.functions.Function1", callback.getString("interface"));
        assertEquals("invoke", callback.getString("method"));
        assertEquals("toggleTranslation", callback.getString("action"));
        assertEquals(4, callback.length());
        JSONObject mask = arguments.getJSONObject(8);
        assertEquals("integer", mask.getString("kind"));
        // Default the first seven parameters (including DEFAULT/150f/false/long press),
        // but never default away the on-click callback at bit 7.
        assertEquals(127, mask.getInt("value"));
        assertEquals(0, mask.getInt("value") & 128);
        assertEquals(2, mask.length());
    }
}
