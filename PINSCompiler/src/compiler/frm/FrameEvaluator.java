/**
 * @ Author: turk
 * @ Description: Analizator klicnih zapisov.
 */

package compiler.frm;

import static common.RequireNonNull.requireNonNull;

import common.Report;
import compiler.common.Visitor;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.Array;
import compiler.parser.ast.type.Atom;
import compiler.parser.ast.type.TypeName;
import compiler.seman.common.NodeDescription;
import compiler.seman.type.type.Type;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Stack;

public class FrameEvaluator implements Visitor {
    /**
     * Opis definicij funkcij in njihovih klicnih zapisov.
     */
    private NodeDescription<Frame> frames;

    /**
     * Opis definicij spremenljivk in njihovih dostopov.
     */
    private NodeDescription<Access> accesses;

    /**
     * Opis vozlišč in njihovih definicij.
     */
    private final NodeDescription<Def> definitions;

    /**
     * Opis vozlišč in njihovih podatkovnih tipov.
     */
    private final NodeDescription<Type> types;

    Stack<Frame.Builder> stack = new Stack<>();
    int scope = 0;

    public FrameEvaluator(
            NodeDescription<Frame> frames,
            NodeDescription<Access> accesses,
            NodeDescription<Def> definitions,
            NodeDescription<Type> types
    ) {
        requireNonNull(frames, accesses, definitions, types);
        this.frames = frames;
        this.accesses = accesses;
        this.definitions = definitions;
        this.types = types;
    }

    @Override
    public void visit(Call call) {
        // TODO Auto-generated method stub
        var a = stack.pop();
        var size = call.arguments.stream().map(args -> types.valueFor(args).get()).mapToInt(type -> type.sizeInBytesAsParam()).sum();
        ArrayList imena = new ArrayList<>(Arrays.asList("print_int","print_str","print_log","rand_int","seed"));
        if(!imena.contains(call.name))
            a.addFunctionCall(size+4);
        else
            a.addFunctionCall(size);
        stack.push(a);
    }

    @Override
    public void visit(Binary binary) {
        // TODO Auto-generated method stub
        binary.left.accept(this);
        binary.right.accept(this);
    }

    @Override
    public void visit(Block block) {
        // TODO Auto-generated method stub
        for (Expr expr : block.expressions){
            expr.accept(this);
        }

    }

    @Override
    public void visit(For forLoop) {
        // TODO Auto-generated method stub
        forLoop.counter.accept(this);
        forLoop.low.accept(this);
        forLoop.high.accept(this);
        forLoop.step.accept(this);
        forLoop.body.accept(this);

    }


    @Override
    public void visit(Name name) {
        // TODO Auto-generated method stub
    }


    @Override
    public void visit(IfThenElse ifThenElse) {
        // TODO Auto-generated method stub
        ifThenElse.condition.accept(this);
        ifThenElse.thenExpression.accept(this);
        ifThenElse.elseExpression.ifPresent(expr -> expr.accept(this));
    }


    @Override
    public void visit(Literal literal) {
        // TODO Auto-generated method stub
    }


    @Override
    public void visit(Unary unary) {
        unary.expr.accept(this);

    }


    @Override
    public void visit(While whileLoop) {
        // TODO Auto-generated method stub
        whileLoop.condition.accept(this);
        whileLoop.body.accept(this);

    }


    @Override
    public void visit(Where where) {
        // TODO Auto-generated method stub
        where.defs.accept(this);
        where.expr.accept(this);
    }


    @Override
    public void visit(Defs defs) {
        // TODO Auto-generated method stub
        for (Def def : defs.definitions){
            if (def instanceof FunDef funDef) {
                funDef.accept(this);
            } else if (def instanceof TypeDef typeDef) {
                typeDef.accept(this);
            } else if (def instanceof VarDef varDef) {
                varDef.accept(this);
            }
        }
    }

    @Override
    public void visit(FunDef funDef) {
        // TODO Auto-generated method stub
        Frame.Builder frm;
        scope++;
        if (scope == 1){
            frm = new Frame.Builder(Frame.Label.named(funDef.name), scope);
        } else{
            frm = new Frame.Builder(Frame.Label.nextAnonymous(), scope);
        }
        frm.addParameter(4);
        for (Parameter param : funDef.parameters){
            var offset = frm.addParameter(types.valueFor(param.type).get().sizeInBytesAsParam());
            accesses.store(new Access.Parameter(types.valueFor(param.type).get().sizeInBytesAsParam(),
                    offset, scope), param);
        }
        stack.push(frm);
        funDef.body.accept(this);
        frames.store(frm.build(), funDef);
        scope--;
        stack.pop();
    }


    @Override
    public void visit(TypeDef typeDef) {
        // TODO Auto-generated method stub
        if (scope == 0){
            accesses.store(new Access.Global(types.valueFor(typeDef).get().sizeInBytes(),
                    Frame.Label.named(typeDef.name)), typeDef);
        } else{
            Frame.Builder b = stack.pop();
            accesses.store(new Access.Local(types.valueFor(typeDef).get().sizeInBytes(),
                    b.addLocalVariable(types.valueFor(typeDef).get().sizeInBytes()), scope), typeDef);
            stack.push(b);
        }

    }

    @Override
    public void visit(VarDef varDef) {
        // TODO Auto-generated method stub
        if (scope == 0){
            accesses.store(new Access.Global(types.valueFor(varDef).get().sizeInBytes(),
                    Frame.Label.named(varDef.name)), varDef);
        } else{
            Frame.Builder b = stack.pop();
            accesses.store(new Access.Local(types.valueFor(varDef).get().sizeInBytes(),
                    b.addLocalVariable(types.valueFor(varDef).get().sizeInBytes()), scope), varDef);
            stack.push(b);
        }

    }

    @Override
    public void visit(Parameter parameter) {
        // TODO Auto-generated method stub
        parameter.type.accept(this);

    }

    @Override
    public void visit(Array array) {
        // TODO Auto-generated method stub
        array.type.accept(this);

    }

    @Override
    public void visit(Atom atom) {
        // TODO Auto-generated method stub

    }

    @Override
    public void visit(TypeName name) {
        // TODO Auto-generated method stub

    }
}


