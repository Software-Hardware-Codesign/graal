package com.oracle.truffle.dsl.processor.operations;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;

import com.oracle.truffle.dsl.processor.ProcessorContext;
import com.oracle.truffle.dsl.processor.TruffleTypes;
import com.oracle.truffle.dsl.processor.java.model.CodeTree;
import com.oracle.truffle.dsl.processor.java.model.CodeTreeBuilder;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeElement;
import com.oracle.truffle.dsl.processor.java.model.CodeTypeMirror;
import com.oracle.truffle.dsl.processor.java.model.CodeVariableElement;
import com.oracle.truffle.dsl.processor.java.transform.AbstractCodeWriter;
import com.oracle.truffle.dsl.processor.operations.Operation.BuilderVariables;
import com.oracle.truffle.dsl.processor.operations.instructions.FrameKind;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction;
import com.oracle.truffle.dsl.processor.operations.instructions.Instruction.ExecutionVariables;

public class OperationGeneratorUtils {

    public static TruffleTypes getTypes() {
        return ProcessorContext.getInstance().getTypes();
    }

    public static String toScreamCase(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").replace('.', '_').toUpperCase();
    }

    public static CodeTree createEmitInstruction(BuilderVariables vars, Instruction instr, CodeVariableElement... arguments) {
        CodeTree[] trees = new CodeTree[arguments.length];
        for (int i = 0; i < trees.length; i++) {
            trees[i] = CodeTreeBuilder.singleVariable(arguments[i]);
        }

        return createEmitInstruction(vars, instr, trees);
    }

    public static CodeTree createEmitInstruction(BuilderVariables vars, Instruction instr, CodeTree... arguments) {
        return instr.createEmitCode(vars, arguments);
    }

    public static CodeTree createCreateLabel() {
        return CodeTreeBuilder.createBuilder().cast(getTypes().BuilderOperationLabel).startCall("createLabel").end().build();
    }

    public static CodeTree createEmitLabel(BuilderVariables vars, CodeTree label) {
        return CodeTreeBuilder.createBuilder().startStatement().startCall("doEmitLabel").variable(vars.bci).tree(label).end(2).build();
    }

    public static CodeTree createEmitLabel(BuilderVariables vars, CodeVariableElement label) {
        return createEmitLabel(vars, CodeTreeBuilder.singleVariable(label));
    }

    public static CodeTree createReadOpcode(CodeTree bc, CodeTree bci) {
        return CodeTreeBuilder.createBuilder()//
                        .startCall("LE_BYTES", "getShort")//
                        .tree(bc).tree(bci).end(1).build();
    }

    public static CodeTree createReadOpcode(CodeVariableElement bc, CodeVariableElement bci) {
        return createReadOpcode(
                        CodeTreeBuilder.singleVariable(bc),
                        CodeTreeBuilder.singleVariable(bci));
    }

    public static CodeTree createWriteOpcode(CodeTree bc, CodeTree bci, CodeTree value) {
        return CodeTreeBuilder.createBuilder().startStatement()//
                        .startCall("LE_BYTES", "putShort")//
                        .tree(bc) //
                        .tree(bci) //
                        .startGroup().cast(new CodeTypeMirror(TypeKind.SHORT)).tree(value).end() //
                        .end(2).build();
    }

    public static CodeTree createWriteOpcode(CodeVariableElement bc, CodeVariableElement bci, CodeVariableElement value) {
        return createWriteOpcode(
                        CodeTreeBuilder.singleVariable(bc),
                        CodeTreeBuilder.singleVariable(bci),
                        CodeTreeBuilder.singleVariable(value));
    }

    public static CodeTree createWriteOpcode(CodeVariableElement bc, String bci, CodeVariableElement value) {
        return createWriteOpcode(
                        CodeTreeBuilder.singleVariable(bc),
                        CodeTreeBuilder.singleString(bci),
                        CodeTreeBuilder.singleVariable(value));
    }

    public static String printCode(Element el) {
        StringWriter wr = new StringWriter();
        new AbstractCodeWriter() {
            {
                writer = wr;
            }

            @Override
            protected Writer createWriter(CodeTypeElement clazz) throws IOException {
                // TODO Auto-generated method stub
                return wr;
            }
        }.visit(el);

        return wr.toString();
    }

    public static CodeTree createInstructionSwitch(OperationsData data, ExecutionVariables vars, Function<Instruction, CodeTree> body) {
        return createInstructionSwitch(data, vars, true, body);
    }

    private static final CodeTree BREAK_TREE = CodeTreeBuilder.createBuilder().statement("break").build();

    public static CodeTree createInstructionSwitch(OperationsData data, ExecutionVariables vars, boolean instrumentation, Function<Instruction, CodeTree> body) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        List<CodeTree> trees = new ArrayList<>();
        List<List<Instruction>> treeInstructions = new ArrayList<>();

        CodeTree defaultTree = body.apply(null);
        boolean hasDefault;

        if (defaultTree == null) {
            hasDefault = false;
        } else {
            hasDefault = true;
            trees.add(defaultTree);
            treeInstructions.add(null);
        }

        outerLoop: for (Instruction instr : data.getInstructions()) {
            if (instr.isInstrumentationOnly() && !instrumentation) {
                continue;
            }

            CodeTree result = body.apply(instr);
            if (result == null) {
                if (hasDefault) {
                    // we have to explicitly do nothing for this instruction, since we have default
                    result = BREAK_TREE;
                } else {
                    continue;
                }
            }

            for (int i = 0; i < trees.size(); i++) {
                if (result.isEqualTo(trees.get(i))) {
                    treeInstructions.get(i).add(instr);
                    continue outerLoop;
                }
            }

            trees.add(result);

            List<Instruction> newList = new ArrayList<>(1);
            newList.add(instr);
            treeInstructions.add(newList);

        }

        b.startSwitch().tree(createReadOpcode(vars.bc, vars.bci)).end().startBlock();
        for (int i = 0; i < trees.size(); i++) {
            if (treeInstructions.get(i) == null) {
                b.caseDefault();
            } else {
                for (Instruction instr : treeInstructions.get(i)) {
                    b.startCase().variable(instr.opcodeIdField).end();
                }
            }
            b.startBlock();
            b.tree(trees.get(i));
            b.end();
        }
        b.end();

        return b.build();
    }

    public static CodeTree callSetResultBoxed(String bciOffset, FrameKind kind) {
        return callSetResultBoxed(bciOffset, toFrameTypeConstant(kind));
    }

    public static CodeTree callSetResultBoxed(String bciOffset, CodeTree kind) {
        CodeTreeBuilder b = CodeTreeBuilder.createBuilder();

        b.startStatement().startCall("doSetResultBoxed");
        b.string("bc");
        b.string("$bci");
        b.string(bciOffset);
        b.tree(kind);
        b.end(2);

        return b.build();
    }

    public static CodeTree toFrameTypeConstant(FrameKind kind) {
        return CodeTreeBuilder.singleString("FRAME_TYPE_" + kind.getFrameName().toUpperCase());
    }
}